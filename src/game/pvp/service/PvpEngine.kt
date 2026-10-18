package game.pvp.service

import exception.BettingClosedException
import exception.NoActiveBetException
import exception.WrongPhaseException
import game.pvp.model.Phase
import game.pvp.model.PvpGame
import game.pvp.model.PvpPhaseFrame
import game.pvp.model.PvpSpinFrame
import game.pvp.model.PvpState
import game.pvp.network.ConnectionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.Player
import model.Round
import model.RoundTable
import org.jetbrains.exposed.sql.and
import org.slf4j.LoggerFactory
import service.PlayerService
import service.ProvablyFair
import service.RoundService
import util.Amount
import util.Currency
import util.Env
import util.runTransactionCatching

/**
 * The PvP jackpot-wheel engine — a single, always-on, shared round loop driven by
 * one coroutine, mirroring [game.telefly.service.TeleFlyEngine]. Players bet
 * TON into a shared pot; each occupies an arc of a 100% wheel sized to
 * their share; a provably-fair number lands the pointer in one arc and that player
 * wins the whole pot (zero house edge).
 *
 * Round lifecycle, forever:
 *   WAITING (<2 players) -> COUNTDOWN (>=2 players, 10s, bets still open) ->
 *   SPINNING (locked; winner decided; wheel animates) -> PAYOUT (~4s) -> WAITING ...
 * If during COUNTDOWN the active players drop below the minimum, it reverts to WAITING.
 *
 * Concurrency mirrors TeleFly exactly: a single [Mutex] guards the in-memory
 * round/bet state and is NEVER held across an operator HTTP call. The pattern is
 * lock -> validate + claim in memory -> unlock -> money work -> re-lock to
 * finalize/revert. Money flows through [RoundService] per player (one shared
 * [Round] per participant: DEBIT on bet/top-up, refund CREDIT on cancel, full-pot
 * CREDIT to the winner at settlement).
 */
class PvpEngine(
    private val provablyFair: ProvablyFair,
    private val roundService: RoundService,
    private val playerService: PlayerService,
    private val registry: ConnectionRegistry,
    private val json: Json
) {
    private val mutex = Mutex()

    private var round: RoundData? = null
    private val bets = HashMap<Long, BetSlot>()
    private val history = ArrayDeque<PvpState.HistoryItem>()
    private var nextOrder = 0

    private lateinit var scope: CoroutineScope
    private var settlementJob: Job? = null

    /** Last good TON seed — reused if a later fetch fails, so a transient TON outage
     *  degrades (reused public seed) rather than killing the loop. */
    private var lastClientSeed: String? = null

    /** The next round's seeds, prepared off-lock during PAYOUT so the blocking TON
     *  fetch never happens while holding the mutex. */
    private var pending: PreparedRound? = null

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            reconcile()
            var prep: PreparedRound? = null
            while (prep == null && scope.isActive) {
                prep = try {
                    prepareRound(0L)
                } catch (e: Exception) {
                    log.error("initial round prepare failed; retrying", e)
                    delay(BOOT_RETRY_MS)
                    null
                }
            }
            if (prep == null) return@launch
            val frame = mutex.withLock {
                installWaiting(prep, Clock.System.now())
                phaseFrameTextLocked()
            }
            registry.broadcast(frame)
            loop()
        }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (e: Exception) {
                log.error("PvP tick failed", e)
            }
            delay(Env.pvpTickInterval)
        }
    }

    private suspend fun tick() {
        val now = Clock.System.now()
        val out = mutex.withLock {
            val r = round ?: return
            when (r.phase) {
                Phase.WAITING ->
                    if (activeCount() >= Env.pvpMinPlayers) {
                        r.phase = Phase.COUNTDOWN
                        r.phaseEndsAt = now + Env.pvpCountdown
                        Out.Send(phaseFrameTextLocked())
                    } else Out.Idle

                Phase.COUNTDOWN ->
                    if (activeCount() < Env.pvpMinPlayers) {
                        r.phase = Phase.WAITING
                        r.phaseEndsAt = Instant.fromEpochMilliseconds(0)
                        Out.Send(phaseFrameTextLocked())
                    } else if (now >= r.phaseEndsAt) {
                        lockAndDecide(r, now)
                    } else Out.Idle

                Phase.SPINNING ->
                    if (now >= r.phaseEndsAt) {
                        r.phase = Phase.PAYOUT
                        r.phaseEndsAt = now + Env.pvpPayoutPause
                        Out.Idle
                    } else Out.Idle

                Phase.PAYOUT ->
                    if (now >= r.phaseEndsAt && settlementDone() && pending != null) {
                        installWaiting(pending!!, now)
                        pending = null
                        Out.Send(phaseFrameTextLocked())
                    } else Out.Idle
            }
        }

        when (out) {
            Out.Idle -> {}
            is Out.Send -> registry.broadcast(out.text)
            is Out.Spin -> {
                registry.broadcast(out.text)
                settlementJob = scope.launch { settle(out.settlements, out.pot, out.winningNumber) }
                // Off-lock: blocking TON fetch is safe here; falls back to the last seed.
                pending = try {
                    prepareRound(out.nonce)
                } catch (e: Exception) {
                    log.error("preparing next round failed", e)
                    null
                }
            }
        }
    }

    private fun settlementDone(): Boolean = settlementJob?.isCompleted != false

    /** Lock the roster, pick the winner from the committed seeds, enter SPINNING. */
    private fun lockAndDecide(r: RoundData, now: Instant): Out {
        val roster = bets.values.filter { it.state == SlotState.ACTIVE }.sortedBy { it.order }
        if (roster.size < Env.pvpMinPlayers) {       // safety: drifted below min at the edge
            r.phase = Phase.WAITING
            r.phaseEndsAt = Instant.fromEpochMilliseconds(0)
            return Out.Send(phaseFrameTextLocked())
        }
        val stakes = roster.map { it.stake.value }
        val pot = stakes.sum()
        val hash = provablyFair.computeHash(r.serverSeed, r.clientSeed, r.roundNonce.toString())
        val fraction = WheelResult.fraction(hash)
        val number = WheelResult.winningNumber(fraction)
        val winnerIdx = WheelResult.winner(fraction, stakes)
        val winner = roster[winnerIdx]

        r.phase = Phase.SPINNING
        r.phaseEndsAt = now + Env.pvpSpinDuration
        r.winningNumber = number
        r.winnerPlayerId = winner.playerId
        roster.forEach { it.state = SlotState.LOCKED }

        history.addLast(PvpState.HistoryItem(number, winner.username, pot))
        while (history.size > HISTORY_MAX) history.removeFirst()

        val spinText = json.encodeToString(
            PvpSpinFrame(
                winningNumber = number,
                winnerUsername = winner.username,
                winnerProfilePic = winner.profilePic,
                pot = pot,
                payout = pot,
                serverSeed = r.serverSeed,
                clientSeed = r.clientSeed,
                roundNonce = r.roundNonce,
                roster = rosterSeatsLocked()
            )
        )
        val settlements = roster.map { Settle(it.playerId, it.sessionToken, it.roundId, it.playerId == winner.playerId) }
        return Out.Spin(spinText, settlements, pot, number, r.roundNonce)
    }

    // ── auth snapshot ─────────────────────────────────────────────────────────

    suspend fun snapshotFor(player: Player, token: String, currency: Currency): PvpState {
        requireTon(currency)
        val view = mutex.withLock {
            val r = round ?: return@withLock null
            LockedView(r, bets[player.id.value], rosterSeatsLocked(), potLocked(), activeCount(), history.toList())
        }
        // Spectator with no slot: fetch the live operator balance (needs a transaction).
        val balance = view?.slot?.balance ?: runTransactionCatching {
            playerService.findBalance(player, token, currency).getOrThrow()
        }.getOrThrow()

        if (view == null) {
            return PvpState(
                player = PvpState.PlayerInfo.from(player, balance),
                phase = Phase.WAITING, roundNonce = 0, serverSeedHash = "", phaseEndsAt = 0,
                pot = 0, players = 0, roster = emptyList(), bet = null, history = emptyList()
            )
        }
        return stateFrom(player, balance, view.r, view.slot, view.roster, view.pot, view.players, view.history)
    }

    // ── bet (place or top-up) ───────────────────────────────────────────────────

    suspend fun bet(player: Player, token: String, currency: Currency, amount: Long): PvpState {
        requireTon(currency)
        require(amount > 0) { "amount must be positive" }
        val playerId = player.id.value

        val step = mutex.withLock {
            val r = round ?: throw WrongPhaseException()
            if (r.phase != Phase.WAITING && r.phase != Phase.COUNTDOWN) throw BettingClosedException()
            // Don't start a debit that can't finish before lock.
            if (r.phase == Phase.COUNTDOWN && r.phaseEndsAt - Clock.System.now() < Env.brandTimeout) {
                throw BettingClosedException()
            }
            val existing = bets[playerId]
            when {
                existing == null -> {
                    bets[playerId] = BetSlot(
                        playerId = playerId, sessionToken = token, currency = currency,
                        username = player.username, profilePic = player.profilePic,
                        roundId = 0L, stake = Amount(0), balance = Amount(0),
                        order = nextOrder++, state = SlotState.PLACING
                    )
                    Step.New
                }
                existing.state == SlotState.ACTIVE -> {
                    existing.state = SlotState.PLACING
                    Step.TopUp(existing.roundId, existing.stake, existing.balance)
                }
                else -> throw WrongPhaseException()   // a money op is already in flight
            }
        }

        // Set when a confirmed debit had to be compensated: the position is voided
        // at the operator, so the slot must leave the roster either way.
        var voided = false
        val result = runCatching {
            when (step) {
                Step.New -> {
                    // The round commits BEFORE the stake moves: the debit runs in
                    // its own transactions and must reference a durable round.
                    // Open the round and read the pre-bet balance CONCURRENTLY — they
                    // are independent (open creates the round + notifies the operator;
                    // the balance is a display read), so overlapping them shaves a full
                    // operator round-trip off the "enter battle" path.
                    val (round, balBefore) = coroutineScope {
                        val roundD = async {
                            runTransactionCatching {
                                val r = roundService.open(player, token, GAME, currency).getOrThrow()
                                // A stale adopted round still carrying a game payload is an
                                // unfinished settle/cancel; betting into it would tangle two
                                // bets' money history — recovery completes it at next boot.
                                check(PvpGame.forRound(r) == null) { "previous round still settling" }
                                r
                            }.getOrThrow()
                        }
                        val balD = async {
                            runCatching { playerService.findBalance(player, token, currency).getOrThrow() }.getOrNull()
                        }
                        roundD.await() to balD.await()
                    }

                    val debit = roundService.debit(player, token, GAME, currency, Amount(amount)).getOrThrow()

                    // Post-bet balance computed from the pre-bet read minus the stake —
                    // the debit is the only movement, so no second operator /balance
                    // call; the WS balance event reconciles any concurrent drift. Fall
                    // back to a fetch only if the pre-read failed.
                    val balance = balBefore?.let { Amount(maxOf(0L, it.value - amount)) }
                        ?: runTransactionCatching { playerService.findBalance(player, token, currency).getOrThrow() }.getOrNull()
                        ?: Amount(0)

                    runTransactionCatching {
                        PvpGame.new {
                            this.round = round
                            this.roundNonce = this@PvpEngine.round?.roundNonce ?: 0L
                            this.sessionToken = token
                            this.placeAmount = Amount(amount)
                            this.balance = balance
                            this.serverSeed = this@PvpEngine.round?.serverSeed ?: ""
                            this.clientSeed = this@PvpEngine.round?.clientSeed ?: ""
                        }
                        round.id.value to balance
                    }.getOrElse { e ->
                        // The operator took the stake but the bet could not be recorded: refund + void.
                        roundService.compensateDebit(player, token, GAME, currency, debit)
                        voided = true
                        throw e
                    }
                }
                is Step.TopUp -> {
                    val debit = roundService.debit(player, token, GAME, currency, Amount(amount)).getOrThrow()

                    // Compute the post-top-up balance from the slot's known balance — no
                    // extra operator /balance call; the WS balance event reconciles.
                    val balance = Amount(maxOf(0L, step.balance.value - amount))
                    runTransactionCatching {
                        Round.findById(step.roundId)?.let { PvpGame.forRound(it) }
                            ?.let { it.placeAmount = Amount(it.placeAmount.value + amount) }
                        step.roundId to balance
                    }.getOrElse { e ->
                        // The top-up is charged but unrecordable: void the WHOLE
                        // position (prior stake + top-up) — a partial refund would
                        // split the money truth between us and the operator.
                        roundService.compensateDebit(player, token, GAME, currency, debit, Amount(step.stake.value + amount))
                        voided = true
                        throw e
                    }
                }
            }
        }

        val reply = mutex.withLock {
            result.fold(
                onSuccess = { (roundId, balance) ->
                    val slot = bets[playerId] ?: throw WrongPhaseException()
                    slot.roundId = roundId
                    slot.stake = Amount(slot.stake.value + amount)
                    slot.balance = balance
                    slot.state = SlotState.ACTIVE
                    Reply(personalStateLocked(player, slot), phaseFrameTextLocked())
                },
                onFailure = { e ->
                    val slot = bets[playerId]
                    if (step is Step.New || voided) bets.remove(playerId) else slot?.state = SlotState.ACTIVE
                    throw e
                }
            )
        }
        registry.broadcast(reply.broadcast)
        return reply.state
    }

    // ── cancel (full refund, leave the round) ────────────────────────────────────

    suspend fun cancel(player: Player, token: String, currency: Currency): PvpState {
        requireTon(currency)
        val playerId = player.id.value

        val (stake, balBefore) = mutex.withLock {
            val r = round ?: throw WrongPhaseException()
            if (r.phase != Phase.WAITING && r.phase != Phase.COUNTDOWN) throw BettingClosedException()
            val slot = bets[playerId] ?: throw NoActiveBetException()
            if (slot.state != SlotState.ACTIVE) throw WrongPhaseException()
            slot.state = SlotState.CANCELLING
            slot.stake to slot.balance
        }

        // Refund credit in its own flow (reuse path): a lost webhook leaves a
        // PENDING row whose id a retried cancel replays — never a second payout.
        val refund = runCatching {
            roundService.credit(player, token, GAME, currency, stake).getOrThrow()
        }
        val result = refund.mapCatching {
            runTransactionCatching {
                roundService.close(player, token, GAME, currency).getOrThrow()
            }.getOrThrow()
            // Post-refund balance computed locally (refund adds the stake back) — no
            // extra operator /balance call; the WS balance event reconciles.
            Amount(balBefore.value + stake.value)
        }

        val reply = mutex.withLock {
            result.fold(
                onSuccess = { balance ->
                    bets.remove(playerId)
                    val r = round!!
                    Reply(
                        PvpState(
                            player = PvpState.PlayerInfo.from(player, balance),
                            phase = r.phase, roundNonce = r.roundNonce, serverSeedHash = r.serverSeedHash,
                            phaseEndsAt = r.phaseEndsAt.toEpochMilliseconds(), pot = potLocked(),
                            players = activeCount(), roster = rosterSeatsLocked(), bet = null,
                            history = history.toList()
                        ),
                        phaseFrameTextLocked()
                    )
                },
                onFailure = { e ->
                    if (refund.isSuccess) {
                        // The refund is confirmed but the close failed: the player is
                        // out — boot recovery closes the round without re-paying (a
                        // CONFIRMED credit short-circuits the reuse path).
                        bets.remove(playerId)
                        log.error("cancel: refund confirmed but round close failed for player $playerId", e)
                    } else {
                        bets[playerId]?.state = SlotState.ACTIVE
                    }
                    throw e
                }
            )
        }
        registry.broadcast(reply.broadcast)
        return reply.state
    }

    // ── settlement & reconciliation (off the tick path, one tx per player) ─────

    private suspend fun settle(settlements: List<Settle>, pot: Long, winningNumber: Double) {
        // Durability first: persist who won and how much BEFORE any webhook, so a
        // failed credit or a restart mid-settle never loses the outcome — a round
        // still open with won/payout set is an unfinished settle that the boot
        // recovery pass (reconcile) completes through the credit reuse path.
        runTransactionCatching {
            for (s in settlements) {
                Round.findById(s.roundId)?.let { PvpGame.forRound(it) }?.apply {
                    this.winningNumber = winningNumber
                    this.won = s.isWinner
                    if (s.isWinner) this.payout = pot
                }
            }
        }.onFailure { log.error("persisting settle outcome failed; settling from memory", it) }

        for (s in settlements) {
            val player = runTransactionCatching { Player.findById(s.playerId) }.getOrNull() ?: continue
            if (s.isWinner) {
                val paid = roundService.credit(player, s.sessionToken, GAME, TON, Amount(pot))
                if (paid.isFailure) {
                    // Keep the round open: the PENDING credit plus the persisted
                    // outcome let recovery resend the SAME transactionId later.
                    log.error("payout credit failed for player ${s.playerId}; round left open for recovery", paid.exceptionOrNull())
                    continue
                }
            }
            runTransactionCatching {
                roundService.close(player, s.sessionToken, GAME, TON).getOrThrow()
            }.onFailure { log.error("settle failed for player ${s.playerId}", it) }
        }
    }

    /**
     * On startup, finish whatever a previous crash/restart left behind. A DECIDED
     * round (won/payout persisted before the first credit attempt) is completed:
     * the winner's payout is resent through the credit reuse path (the SAME
     * pending transactionId — the operator deduplicates a replay), losers just
     * close (their stake is in the pot). An UNDECIDED round is voided: refund the
     * stake and close — the server (not the player) failed, so a net-zero refund
     * is the defensible outcome — and closing frees the uniq_active_round index.
     */
    private suspend fun reconcile() {
        val orphanIds = runTransactionCatching {
            Round.find { (RoundTable.game eq GAME.value) and RoundTable.closedAt.isNull() }
                .map { it.id.value }
        }.getOrElse {
            log.error("reconcile: failed to load orphan rounds", it)
            emptyList()
        }

        for (roundId in orphanIds) {
            val outcome = runTransactionCatching {
                val round = Round.findById(roundId) ?: return@runTransactionCatching null
                val game = PvpGame.forRound(round)
                if (game == null) {
                    round.closedAt = Clock.System.now()       // no audit row; just free the index
                    return@runTransactionCatching null
                }
                Recovery(round.player, game.sessionToken, round.currency, game.won, game.payout, game.placeAmount)
            }.getOrElse {
                log.error("reconcile orphan round $roundId failed", it)
                null
            } ?: continue

            runCatching {
                val credit = when {
                    outcome.won == null -> outcome.stake                  // never decided: void the bet
                    outcome.won == false -> null                          // decided loss: the stake went to the pot
                    outcome.payout != null -> Amount(outcome.payout)      // decided win: resend the pot
                    else -> outcome.stake.also {
                        log.error("reconcile: won round $roundId has no persisted payout; refunding stake")
                    }
                }
                credit?.let {
                    roundService.credit(outcome.player, outcome.sessionToken, GAME, outcome.currency, it).getOrThrow()
                }
                roundService.close(outcome.player, outcome.sessionToken, GAME, outcome.currency).getOrThrow()
            }.onFailure { log.error("reconcile orphan round $roundId failed", it) }
        }
    }

    // ── helpers (all the *Locked ones must be called under the mutex) ─────────────

    private fun prepareRound(prevNonce: Long): PreparedRound {
        val serverSeed = provablyFair.generateServerSeed()
        val serverSeedHash = provablyFair.hashServerSeed(serverSeed)
        val clientSeed = try {
            provablyFair.getTonSeed().also { lastClientSeed = it }
        } catch (e: Exception) {
            lastClientSeed?.also { log.warn("getTonSeed failed; reusing last client seed", e) } ?: throw e
        }
        return PreparedRound(prevNonce + 1, serverSeed, serverSeedHash, clientSeed)
    }

    private fun installWaiting(prep: PreparedRound, now: Instant) {
        round = RoundData(
            phase = Phase.WAITING,
            roundNonce = prep.nonce,
            serverSeed = prep.serverSeed,
            serverSeedHash = prep.serverSeedHash,
            clientSeed = prep.clientSeed,
            phaseEndsAt = Instant.fromEpochMilliseconds(0)
        )
        bets.clear()
        nextOrder = 0
    }

    private fun activeCount(): Int =
        bets.values.count { it.state == SlotState.ACTIVE || it.state == SlotState.LOCKED }

    private fun potLocked(): Long =
        bets.values.filter { it.state == SlotState.ACTIVE || it.state == SlotState.LOCKED }.sumOf { it.stake.value }

    private fun rosterSeatsLocked(): List<PvpState.Seat> =
        bets.values
            .filter { it.state == SlotState.ACTIVE || it.state == SlotState.LOCKED }
            .sortedBy { it.order }
            .map { PvpState.Seat(it.order, it.username, it.stake.value, it.profilePic) }

    private fun phaseFrameTextLocked(): String {
        val r = round!!
        return json.encodeToString(
            PvpPhaseFrame(
                phase = r.phase, roundNonce = r.roundNonce, serverSeedHash = r.serverSeedHash,
                phaseEndsAt = r.phaseEndsAt.toEpochMilliseconds(), pot = potLocked(),
                players = activeCount(), roster = rosterSeatsLocked(), history = history.toList()
            )
        )
    }

    /** Build a personal state from an already-known slot (no operator call). */
    private fun personalStateLocked(player: Player, slot: BetSlot): PvpState =
        stateFrom(player, slot.balance, round!!, slot, rosterSeatsLocked(), potLocked(), activeCount(), history.toList())

    private fun stateFrom(
        player: Player, balance: Amount, r: RoundData, slot: BetSlot?,
        roster: List<PvpState.Seat>, pot: Long, players: Int, history: List<PvpState.HistoryItem>
    ): PvpState {
        val bet = slot?.let {
            val status = when {
                r.phase == Phase.SPINNING || r.phase == Phase.PAYOUT ->
                    if (r.winnerPlayerId == it.playerId) PvpState.BetStatus.WON else PvpState.BetStatus.LOST
                else -> PvpState.BetStatus.PLACED
            }
            PvpState.BetView(it.order, it.stake.value, status)
        }
        return PvpState(
            player = PvpState.PlayerInfo.from(player, balance),
            phase = r.phase, roundNonce = r.roundNonce, serverSeedHash = r.serverSeedHash,
            phaseEndsAt = r.phaseEndsAt.toEpochMilliseconds(), pot = pot, players = players,
            roster = roster, bet = bet, history = history
        )
    }

    private fun requireTon(currency: Currency) =
        require(currency == TON) { "PvP is played in TON only" }

    // ── in-memory types ───────────────────────────────────────────────────────

    private class RoundData(
        var phase: Phase,
        val roundNonce: Long,
        val serverSeed: String,
        val serverSeedHash: String,
        val clientSeed: String,
        var phaseEndsAt: Instant,
        var winningNumber: Double = -1.0,
        var winnerPlayerId: Long = 0L
    )

    private enum class SlotState { PLACING, ACTIVE, CANCELLING, LOCKED }

    private class BetSlot(
        val playerId: Long,
        val sessionToken: String,
        val currency: Currency,
        val username: String,
        val profilePic: String?,
        var roundId: Long,
        var stake: Amount,
        var balance: Amount,
        val order: Int,
        var state: SlotState
    )

    private class PreparedRound(
        val nonce: Long,
        val serverSeed: String,
        val serverSeedHash: String,
        val clientSeed: String
    )

    private class LockedView(
        val r: RoundData,
        val slot: BetSlot?,
        val roster: List<PvpState.Seat>,
        val pot: Long,
        val players: Int,
        val history: List<PvpState.HistoryItem>
    )

    private class Settle(val playerId: Long, val sessionToken: String, val roundId: Long, val isWinner: Boolean)

    private class Recovery(
        val player: Player,
        val sessionToken: String,
        val currency: Currency,
        val won: Boolean?,
        val payout: Long?,
        val stake: Amount
    )

    private class Reply(val state: PvpState, val broadcast: String)

    private sealed interface Step {
        data object New : Step
        class TopUp(val roundId: Long, val stake: Amount, val balance: Amount) : Step
    }

    private sealed interface Out {
        data object Idle : Out
        class Send(val text: String) : Out
        class Spin(
            val text: String,
            val settlements: List<Settle>,
            val pot: Long,
            val winningNumber: Double,
            val nonce: Long
        ) : Out
    }

    private companion object {
        val GAME = PvpGame.GAME
        val TON = Currency.TON
        const val HISTORY_MAX = 25
        const val BOOT_RETRY_MS = 2000L
        private val log = LoggerFactory.getLogger(PvpEngine::class.java)
    }
}
