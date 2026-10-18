package game.lottery.service

import exception.BettingClosedException
import exception.TicketAlreadyBoughtException
import exception.TicketLimitReachedException
import exception.WrongPhaseException
import game.lottery.model.JackpotFrame
import game.lottery.model.Lottery
import game.lottery.model.Lottery.Status
import game.lottery.model.LotteryState
import game.lottery.model.LotteryTable
import game.lottery.model.LotteryTicket
import game.lottery.model.LotteryTicketDto
import game.lottery.model.LotteryTicketTable
import game.lottery.network.ConnectionRegistry
import game.lottery.network.JackpotRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.slf4j.LoggerFactory
import service.ProvablyFair
import service.RoundService
import util.Amount
import util.Currency
import util.Env
import util.runTransactionCatching

/**
 * The lottery engine — one always-on, shared, PERSISTED draw loop driven by a
 * single coroutine (mirrors [game.pvp.service.PvpEngine]/[game.telefly.service.TeleFlyEngine],
 * but the aggregate lives in the DB so a restart resumes the same round). The pool is
 * one jackpot for ALL brands (TON only): players buy system tickets into a pot seeded
 * at 1000 TON; every [Env.lotteryRoundDuration] (2h) boundary sales close and six
 * numbers are revealed one per interval; tickets holding all six split the pool minus
 * commission; with no winner the whole pool rolls into the next round.
 *
 * System ticket: a player buys ONE ticket per draw ([Env.lotteryMaxTicketsPerPlayer])
 * with 6..[Env.lotteryMaxPicks] numbers. The ticket covers C(n, 6) combinations but is
 * priced quadratically: [Env.lotteryTicketPrice] × (n − 5)² (6 numbers = base price,
 * 36 numbers = 961 × base) — a volume discount versus full system-entry pricing, kept
 * steep enough that price always exceeds chance × pool for realistic pools. A ticket
 * wins when ALL drawn numbers are among its picks; the reported win chance is
 * combinations covered over C(range, picks). Each ticket is its own round (DEBIT on
 * purchase, winner CREDIT at settlement, every round closed).
 *
 * Lifecycle, forever:
 *   SELLING (a 2h round) -> DRAWING (six numbers, one per interval) ->
 *   SETTLING (decide + pay winners) -> SETTLED (brief pause) -> SELLING (next round) ...
 *
 * Concurrency mirrors PvP exactly: one [Mutex] guards the in-memory runtime and is
 * NEVER held across an operator HTTP call or the blocking TON fetch. The pattern is
 * lock -> validate + claim in memory -> unlock -> money/IO work -> re-lock to finalize.
 * Money flows through [RoundService] per ticket round, resolved by round id (a player
 * holds several open lottery rounds at once). Durability matches PvP: outcomes are
 * persisted on the ticket BEFORE the credit webhook, and boot recovery completes any
 * unfinished settle through the credit reuse path.
 */
class LotteryEngine(
    private val provablyFair: ProvablyFair,
    private val roundService: RoundService,
    private val registry: ConnectionRegistry,
    private val jackpot: JackpotRegistry,
    private val json: Json
) {
    private val mutex = Mutex()

    private var current: Runtime? = null

    /** playerId -> their tickets this draw (a player may hold up to [maxTickets]). */
    private val tickets = HashMap<Long, MutableList<TicketSlot>>()

    /** Players with a buy in flight (serializes one purchase at a time per player so the
     *  cap check and the salesPool update stay consistent). */
    private val reserving = HashSet<Long>()

    private lateinit var scope: CoroutineScope

    /** Tracks the in-flight async transition (start draw / settle / open next) so the
     *  tick never launches a second one while one is running. */
    private var busy: Job? = null

    private val numbers = Env.lotteryNumbers

    private val maxPicks = Env.lotteryMaxPicks

    private val maxTickets = Env.lotteryMaxTicketsPerPlayer

    /** Total distinct combinations — the denominator of every player's win chance. */
    private val totalCombos = combinations(Env.lotteryRange, numbers)

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            var booted = false
            while (!booted && scope.isActive) {
                booted = try {
                    boot()
                    true
                } catch (e: Exception) {
                    log.error("lottery boot failed; retrying", e)
                    delay(BOOT_RETRY_MS)
                    false
                }
            }
            if (booted) loop()
        }
    }

    /** Load or create the active lottery, resuming whatever a restart left behind. */
    private suspend fun boot() {
        sweepOrphanRounds()
        val latest = loadLatest()?.let { clampStaleSelling(it) }

        val runtime: Runtime = if (latest == null || latest.status == Status.SETTLED) {
            val snap = createNext() ?: error("createNext failed")
            mutex.withLock { tickets.clear(); reserving.clear() }
            runtimeOf(snap, sales = 0L, status = Status.SELLING, nextRevealAt = EPOCH0)
        } else {
            val (slots, sales) = loadTickets(latest.id)
            mutex.withLock { tickets.clear(); tickets.putAll(slots); reserving.clear() }
            val now = Clock.System.now()
            when (latest.status) {
                Status.SELLING -> runtimeOf(latest, sales, Status.SELLING, EPOCH0)
                Status.DRAWING ->
                    if (latest.drawnCount >= numbers) runtimeOf(latest, sales, Status.SETTLING, EPOCH0)
                    else runtimeOf(latest, sales, Status.DRAWING, now + Env.lotteryDrawInterval)
                Status.SETTLING -> runtimeOf(latest, sales, Status.SETTLING, EPOCH0)
                Status.SETTLED -> error("unreachable")
            }
        }

        mutex.withLock { current = runtime }
        if (runtime.status == Status.SETTLING) busy = scope.launch { runSettle(runtime.lotteryId) }
        broadcastState()
    }

    /**
     * Migration guard: a resumed SELLING draw whose endsAt lies beyond the next
     * schedule boundary was opened under an older (longer) schedule. Force it to
     * close NOW — the first tick then runs the normal draw/settle path (numbers,
     * payouts, rollover; the pool loses nothing) and the next round opens on the
     * 2-hour boundary. Idempotent: once clamped, endsAt is in the past.
     */
    private suspend fun clampStaleSelling(snap: LotterySnapshot): LotterySnapshot {
        if (snap.status != Status.SELLING) return snap
        val now = Clock.System.now()
        if (snap.endsAt <= LotterySchedule.nextDrawAfter(now)) return snap

        val persisted = runTransactionCatching {
            Lottery[snap.id].let { if (it.status == Status.SELLING) it.endsAt = now }
        }.isSuccess
        if (!persisted) {
            log.error("lottery boot: clamping stale draw ${snap.id} failed; keeping endsAt=${snap.endsAt}")
            return snap
        }
        log.info("lottery boot: stale draw ${snap.id} (endsAt=${snap.endsAt}) forced to close now")
        return LotterySnapshot(
            id = snap.id,
            status = snap.status,
            startsAt = snap.startsAt,
            endsAt = now,
            seedPool = snap.seedPool,
            serverSeed = snap.serverSeed,
            serverSeedHash = snap.serverSeedHash,
            tonSeed = snap.tonSeed,
            winningNumbers = snap.winningNumbers,
            drawnCount = snap.drawnCount
        )
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (e: Exception) {
                log.error("lottery tick failed", e)
            }
            delay(Env.lotteryTickInterval)
        }
    }

    private suspend fun tick() {
        val now = Clock.System.now()
        val action = mutex.withLock {
            val c = current ?: return
            if (busy?.isCompleted == false) return
            when (c.status) {
                Status.SELLING ->
                    if (now >= c.endsAt) Action.StartDraw else Action.Idle

                Status.DRAWING ->
                    when {
                        c.drawnCount >= numbers -> {
                            c.status = Status.SETTLING
                            Action.BeginSettle(c.lotteryId)
                        }
                        now >= c.nextRevealAt -> {
                            c.drawnCount += 1
                            c.nextRevealAt = now + Env.lotteryDrawInterval
                            Action.Reveal(c.lotteryId, c.drawnCount)
                        }
                        else -> Action.Idle
                    }

                // A settle in progress holds `busy`; once it frees up still SETTLING,
                // the decide did not persist — relaunch it (decide is idempotent).
                Status.SETTLING -> Action.BeginSettle(c.lotteryId)

                Status.SETTLED ->
                    if (now >= c.settledPauseEndsAt) Action.OpenNext else Action.Idle
            }
        }

        when (action) {
            Action.Idle -> {}
            is Action.Reveal -> {
                persistDrawnCount(action.lotteryId, action.count)
                broadcastState()
            }
            Action.StartDraw -> busy = scope.launch { startDraw() }
            is Action.BeginSettle -> busy = scope.launch { runSettle(action.lotteryId) }
            Action.OpenNext -> busy = scope.launch { openNext() }
        }
    }

    // ── transitions (async, off the tick path) ─────────────────────────────────

    /** Sales closed: capture the public TON seed, commit the six numbers, enter DRAWING. */
    private suspend fun startDraw() {
        val c0 = mutex.withLock { current } ?: return
        if (c0.status != Status.SELLING) return
        val lotteryId = c0.lotteryId
        val serverSeed = c0.serverSeed

        val tonSeed = try {
            provablyFair.getTonSeed()
        } catch (e: Exception) {
            log.error("lottery draw: TON seed fetch failed; retrying next tick", e)
            return
        }

        val drawn = LotteryDraw.numbers(
            provablyFair.computeHash(serverSeed, tonSeed, lotteryId.toString()),
            numbers,
            Env.lotteryRange
        )
        val csv = drawn.joinToString(",")

        val persisted = runTransactionCatching {
            Lottery[lotteryId].let { l ->
                if (l.status == Status.SELLING) {
                    l.status = Status.DRAWING
                    l.tonSeed = tonSeed
                    l.winningNumbers = csv
                    l.drawnCount = 0
                }
            }
        }.isSuccess
        if (!persisted) {
            log.error("lottery draw: persisting draw start failed; retrying next tick")
            return
        }

        val ok = mutex.withLock {
            val c = current
            if (c == null || c.lotteryId != lotteryId) {
                false
            } else {
                c.status = Status.DRAWING
                c.tonSeed = tonSeed
                c.winningNumbers = drawn
                c.drawnCount = 0
                c.nextRevealAt = Clock.System.now() + Env.lotteryDrawInterval
                true
            }
        }
        if (ok) broadcastState()
    }

    private suspend fun persistDrawnCount(lotteryId: Long, count: Int) {
        runTransactionCatching {
            Lottery[lotteryId].let { if (it.status == Status.DRAWING) it.drawnCount = count }
        }.onFailure { log.error("lottery: persisting drawnCount failed", it) }
    }

    /** Decide winners and pay them, then enter the SETTLED pause. Idempotent / re-runnable. */
    private suspend fun runSettle(lotteryId: Long) {
        runTransactionCatching {
            Lottery[lotteryId].let { if (it.status == Status.DRAWING) it.status = Status.SETTLING }
        }.onFailure { log.error("lottery settle: persisting SETTLING failed", it) }

        val outcomes = decideAndSettle(lotteryId) ?: run {
            // The decision did not persist (transient DB failure). Stay SETTLING so the
            // tick/boot relaunch re-runs the idempotent settle — NEVER advance to the
            // terminal SETTLED undecided, which would strand winners unpaid and roll the
            // whole pool away with no recovery.
            log.error("lottery settle: decide did not persist for $lotteryId; staying SETTLING for retry")
            return
        }

        runTransactionCatching {
            Lottery[lotteryId].let { it.status = Status.SETTLED; it.settledAt = Clock.System.now() }
        }.onFailure { log.error("lottery settle: persisting SETTLED failed", it) }

        val ok = mutex.withLock {
            val c = current
            if (c == null || c.lotteryId != lotteryId) {
                false
            } else {
                c.status = Status.SETTLED
                c.settledPauseEndsAt = Clock.System.now() + Env.lotterySettlePause
                // Stamp every decided ticket's outcome onto its in-memory slot.
                outcomes.forEach { (ticketId, o) ->
                    tickets[o.playerId]?.firstOrNull { it.id == ticketId }?.let { it.won = o.won; it.payout = o.payout }
                }
                true
            }
        }
        if (ok) broadcastState()
    }

    /**
     * Phase 1 (one tx): match every ticket against the drawn set and persist its
     * outcome (matched + payout) BEFORE any credit, so a crash mid-settle is completed
     * by recovery through the credit reuse path. Net pool = gross * (100 - commission)%
     * split equally among ALL winning tickets; with no winner nothing is paid and the
     * whole pool rolls over (decided in [createNext]).
     * Phase 2 (per ticket): credit each winning ticket's round and close every round.
     * Idempotent — already-decided tickets keep their outcome and credits dedupe on
     * the round's existing transaction id.
     */
    private suspend fun decideAndSettle(lotteryId: Long): Map<Long, Outcome>? {
        val plan = runTransactionCatching {
            val lot = Lottery[lotteryId]
            val winning = lot.winningList().toSet()
            val tks = LotteryTicket.find { LotteryTicketTable.lottery eq lot.id }.toList()
            val sales = tks.sumOf { it.amount.value }
            val gross = lot.seedPool + sales
            // System ticket: a winner holds ALL drawn numbers among its picks.
            val winners = tks.filter { it.numbersList().toSet().containsAll(winning) }
            val net = if (winners.isEmpty()) 0L else gross - gross * Env.lotteryCommissionPercent / 100
            val share = if (winners.isEmpty()) 0L else net / winners.size

            tks.forEach { t ->
                if (t.matched == null) {
                    val isWinner = t in winners
                    t.matched = isWinner
                    t.payout = if (isWinner) share else null
                }
            }
            lot.netPool = net
            lot.winnerCount = winners.size

            tks.map {
                SettleItem(
                    ticketId = it.id.value,
                    playerId = it.player.id.value,
                    sessionToken = it.sessionToken,
                    roundId = it.round.id.value,
                    isWinner = it.matched == true,
                    payout = it.payout ?: 0L
                )
            }
        }.getOrElse {
            // Distinct failure signal: null means the decision did not persist, whereas
            // an empty plan legitimately means a draw with zero tickets.
            log.error("lottery decide failed for $lotteryId", it)
            return null
        }

        val outcomes = HashMap<Long, Outcome>()
        for (s in plan) {
            val player = runTransactionCatching { Player.findById(s.playerId) }.getOrNull() ?: continue
            if (s.isWinner && s.payout > 0L) {
                val paid = roundService.credit(s.roundId, player, s.sessionToken, Amount(s.payout))
                if (paid.isFailure) {
                    log.error("lottery payout failed for ticket ${s.ticketId}; round left open for recovery", paid.exceptionOrNull())
                    outcomes[s.ticketId] = Outcome(s.playerId, won = true, payout = s.payout)
                    continue
                }
            }
            runTransactionCatching { roundService.close(s.roundId, player, s.sessionToken).getOrThrow() }
                .onFailure { log.error("lottery close failed for ticket ${s.ticketId}", it) }
            outcomes[s.ticketId] = Outcome(s.playerId, won = s.isWinner, payout = if (s.isWinner) s.payout else 0L)
        }
        return outcomes
    }

    /** Open the next round. Seed = 1000 TON at genesis/after a win; the full prior pool on a no-winner rollover. */
    private suspend fun openNext() {
        sweepOrphanRounds()
        val snap = createNext() ?: run {
            log.error("lottery openNext: createNext failed; retrying next tick")
            return
        }
        mutex.withLock {
            tickets.clear()
            reserving.clear()
            current = runtimeOf(snap, sales = 0L, status = Status.SELLING, nextRevealAt = EPOCH0)
        }
        broadcastState()
    }

    private suspend fun createNext(): LotterySnapshot? =
        runTransactionCatching {
            val prev = Lottery.all().orderBy(LotteryTable.id to SortOrder.DESC).limit(1).firstOrNull()
            val seed = when {
                prev == null -> Env.lotteryBasePool
                (prev.winnerCount ?: 0) == 0 -> {
                    // No winner: the whole pool (seed + all sales) rolls into the next round.
                    val sales = LotteryTicket.find { LotteryTicketTable.lottery eq prev.id }.sumOf { it.amount.value }
                    prev.seedPool + sales
                }
                else -> Env.lotteryBasePool   // a jackpot was won: reseed the guaranteed floor
            }
            val now = Clock.System.now()
            val endsAt = LotterySchedule.nextDrawAfter(now)
            // Each draw is one round; a new game starts strictly on the 2-hour boundary.
            // Genesis keeps its real boot time (it legitimately starts mid-round the first time).
            val startsAt = if (prev == null) now else endsAt.minus(Env.lotteryRoundDuration)
            val serverSeed = provablyFair.generateServerSeed()
            val lottery = Lottery.new {
                this.currency = CURRENCY
                this.status = Status.SELLING
                this.startsAt = startsAt
                this.endsAt = endsAt
                this.seedPool = seed
                this.serverSeed = serverSeed
                this.serverSeedHash = provablyFair.hashServerSeed(serverSeed)
                this.tonSeed = null
                this.winningNumbers = ""
                this.drawnCount = 0
            }
            snapshot(lottery)
        }.getOrElse {
            log.error("lottery createNext failed", it)
            null
        }

    /**
     * Finish any lottery round left open by a crashed/failed settle: pay the ticket's
     * winner (reuse path → same transactionId, operator dedupes) per the ticket's
     * persisted outcome and close. A round with no ticket is just closed to free it.
     * Only SETTLED lotteries are touched — live tickets (SELLING/DRAWING) and a
     * still-settling draw (SETTLING, owned by runSettle) are left alone. Never throws.
     */
    private suspend fun sweepOrphanRounds() {
        val ids = runTransactionCatching {
            Round.find { (RoundTable.game eq GAME.value) and RoundTable.closedAt.isNull() }.map { it.id.value }
        }.getOrElse {
            log.error("lottery sweep: loading orphan rounds failed", it)
            emptyList()
        }

        for (roundId in ids) {
            val rec = runTransactionCatching {
                val round = Round.findById(roundId) ?: return@runTransactionCatching null
                val ticket = LotteryTicket.forRound(round)
                if (ticket == null) {
                    round.closedAt = Clock.System.now()
                    return@runTransactionCatching null
                }
                // Only a fully SETTLED lottery is safe to finish here: SELLING/DRAWING
                // tickets are still live, and a SETTLING lottery is owned by the
                // (re)launched runSettle — which credits winners while the round is still
                // open — so racing it here would close a winner's round unpaid.
                if (ticket.lottery.status != Status.SETTLED) return@runTransactionCatching null
                OrphanRec(round.player.id.value, ticket.sessionToken, ticket.matched == true, ticket.payout ?: 0L)
            }.getOrElse {
                log.error("lottery sweep: round $roundId failed", it)
                null
            } ?: continue

            runCatching {
                val player = runTransactionCatching { Player.findById(rec.playerId) }.getOrNull() ?: return@runCatching
                if (rec.isWinner && rec.payout > 0L) {
                    roundService.credit(roundId, player, rec.sessionToken, Amount(rec.payout)).getOrThrow()
                }
                roundService.close(roundId, player, rec.sessionToken).getOrThrow()
            }.onFailure { log.error("lottery sweep: settling orphan round $roundId failed", it) }
        }
    }

    // ── socket/REST-facing operations ────────────────────────────────────────────

    /** auth/state reply: this player's full snapshot (loads their tickets once if unknown). */
    suspend fun snapshotFor(player: Player): LotteryState {
        val playerId = player.id.value
        val lotteryId = mutex.withLock { current?.lotteryId } ?: throw WrongPhaseException()

        val known = mutex.withLock { tickets.containsKey(playerId) }
        if (!known) {
            val slots = runTransactionCatching {
                val lot = Lottery[lotteryId]
                LotteryTicket.find { (LotteryTicketTable.lottery eq lot.id) and (LotteryTicketTable.player eq player.id) }
                    .map { TicketSlot(it.id.value, it.numbersList(), it.matched, it.payout) }
                    .toMutableList()
            }.getOrNull()
            if (slots != null) mutex.withLock { if (current?.lotteryId == lotteryId && !tickets.containsKey(playerId)) tickets[playerId] = slots }
        }
        return personalState(playerId)
    }

    /** Buy this draw's single system ticket (6..maxPicks distinct numbers). DEBIT
     *  priceOf(n) (base × (n−5)²) into the pool. */
    suspend fun buy(player: Player, token: String, currency: Currency, rawNumbers: List<Int>): LotteryState {
        requireTon(currency)
        val chosen = validateNumbers(rawNumbers)
        val price = priceOf(chosen.size)
        val playerId = player.id.value

        val lotteryId = mutex.withLock {
            val c = current ?: throw WrongPhaseException()
            val now = Clock.System.now()
            if (c.status != Status.SELLING || now >= c.endsAt) throw BettingClosedException()
            // Don't start a debit that can't finish before the draw boundary.
            if (c.endsAt - now < Env.brandTimeout) throw BettingClosedException()
            if (playerId in reserving) throw TicketAlreadyBoughtException()       // a buy is already in flight
            if ((tickets[playerId]?.size ?: 0) >= maxTickets) throw TicketLimitReachedException(maxTickets)
            reserving.add(playerId)
            c.lotteryId
        }

        val result = runCatching {
            val round = roundService.openNew(player, token, GAME, CURRENCY).getOrThrow()
            val roundId = round.id.value

            val debit = roundService.debit(roundId, player, token, Amount(price)).getOrThrow()

            runTransactionCatching {
                LotteryTicket.new {
                    this.lottery = Lottery[lotteryId]
                    this.round = round
                    this.player = player
                    this.sessionToken = token
                    this.numbers = chosen.joinToString(",")
                    this.amount = Amount(price)
                }.id.value
            }.getOrElse { e ->
                // Price taken but the ticket could not be recorded: refund + retire the round.
                roundService.compensateDebit(roundId, player, token, debit)
                throw e
            }
        }

        mutex.withLock {
            reserving.remove(playerId)
            result.fold(
                onSuccess = { ticketId ->
                    if (current?.lotteryId == lotteryId) {
                        tickets.getOrPut(playerId) { mutableListOf() }.add(TicketSlot(ticketId, chosen, null, null))
                        current?.let { it.salesPool += price }
                    }
                },
                onFailure = { throw it }
            )
        }

        broadcastState()
        return personalState(playerId)
    }

    // ── snapshot building ───────────────────────────────────────────────────────

    private suspend fun broadcastState() {
        val (shared, view) = mutex.withLock {
            val c = current ?: return
            sharedLocked(c) to snapshotTicketsLocked()
        }
        registry.broadcastPersonalized { playerId -> json.encodeToString(stateFor(playerId, shared, view)) }
        jackpot.broadcast(json.encodeToString(jackpotOf(shared)))
    }

    /** The current public jackpot frame, or null before the first draw is loaded. */
    suspend fun currentJackpot(): String? {
        val shared = mutex.withLock { current?.let { sharedLocked(it) } } ?: return null
        return json.encodeToString(jackpotOf(shared))
    }

    private suspend fun personalState(playerId: Long): LotteryState {
        val (shared, view) = mutex.withLock {
            val c = current ?: throw WrongPhaseException()
            sharedLocked(c) to snapshotTicketsLocked()
        }
        return stateFor(playerId, shared, view)
    }

    private fun snapshotTicketsLocked(): Map<Long, List<TicketView>> =
        tickets.mapValues { (_, slots) -> slots.map { TicketView(it.id, it.numbers, it.won, it.payout) } }

    private fun sharedLocked(c: Runtime): Shared {
        val now = Clock.System.now()
        val finished = c.status == Status.SETTLED
        return Shared(
            prizePool = c.seedPool + c.salesPool,
            currency = CURRENCY.value,
            ticketPrice = Env.lotteryTicketPrice,
            startsAt = c.startsAt.toEpochMilliseconds(),
            endsAt = c.endsAt.toEpochMilliseconds(),
            winningNumbers = c.winningNumbers.take(c.drawnCount),
            bettingOpen = c.status == Status.SELLING && now < c.endsAt,
            finished = finished,
            status = c.status.name,
            drawNonce = c.lotteryId,
            serverSeedHash = c.serverSeedHash,
            serverSeed = if (finished) c.serverSeed else null,
            tonSeed = if (finished) c.tonSeed else null
        )
    }

    private fun jackpotOf(s: Shared) = JackpotFrame(
        currency = s.currency,
        startAt = s.startsAt,
        endAt = s.endsAt,
        prizePool = s.prizePool,
        status = s.status
    )

    private fun stateFor(playerId: Long, shared: Shared, view: Map<Long, List<TicketView>>): LotteryState {
        val mine = view[playerId] ?: emptyList()
        val covered = mine.sumOf { combinations(it.numbers.size, numbers) }.coerceAtMost(totalCombos)
        val winChance = if (totalCombos > 0) covered.toDouble() / totalCombos.toDouble() else 0.0
        return LotteryState(
            prizePool = shared.prizePool,
            currency = shared.currency,
            ticketPrice = shared.ticketPrice,
            tickets = mine.map { LotteryTicketDto(it.id, it.numbers, it.won, it.payout) },
            ticketCount = mine.size,
            maxTickets = maxTickets,
            minPicks = numbers,
            maxPicks = maxPicks,
            winChance = winChance,
            startsAt = shared.startsAt,
            endsAt = shared.endsAt,
            winningNumbers = shared.winningNumbers,
            bettingOpen = shared.bettingOpen,
            finished = shared.finished,
            drawNonce = shared.drawNonce,
            serverSeedHash = shared.serverSeedHash,
            serverSeed = shared.serverSeed,
            tonSeed = shared.tonSeed
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun validateNumbers(raw: List<Int>): List<Int> {
        require(raw.size in numbers..maxPicks) { "ticket must have $numbers..$maxPicks numbers" }
        require(raw.all { it in 1..Env.lotteryRange }) { "numbers must be in 1..${Env.lotteryRange}" }
        require(raw.toSet().size == raw.size) { "numbers must be distinct" }
        return raw.sorted()
    }

    /** Ticket price: base price × (n − picks + 1)². Quadratic in the extra numbers,
     *  NOT proportional to C(n, picks) — full system-entry pricing dwarfs our pool
     *  (C(36,6) × base ≈ 19.5M), so big tickets get a steep volume discount while
     *  the price still grows fast enough that price > chance × pool for any
     *  realistic pool (36 picks: 961 × base vs a 13.9% chance). */
    private fun priceOf(count: Int): Long {
        val steps = (count - numbers + 1).toLong()
        return Math.multiplyExact(Env.lotteryTicketPrice, Math.multiplyExact(steps, steps))
    }

    private fun requireTon(currency: Currency) =
        require(currency == CURRENCY) { "Lottery is played in ${CURRENCY.value} only" }

    /** C(n, k) — integer at every step (running value is C(n - k + i, i)). */
    private fun combinations(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0L
        var result = 1L
        for (i in 1..k) result = result * (n - k + i) / i
        return result
    }

    private suspend fun loadLatest(): LotterySnapshot? =
        runTransactionCatching {
            Lottery.all().orderBy(LotteryTable.id to SortOrder.DESC).limit(1).firstOrNull()?.let { snapshot(it) }
        }.getOrNull()

    private suspend fun loadTickets(lotteryId: Long): Pair<Map<Long, MutableList<TicketSlot>>, Long> =
        runTransactionCatching {
            val lot = Lottery[lotteryId]
            val map = HashMap<Long, MutableList<TicketSlot>>()
            var sales = 0L
            LotteryTicket.find { LotteryTicketTable.lottery eq lot.id }.forEach { t ->
                map.getOrPut(t.player.id.value) { mutableListOf() }
                    .add(TicketSlot(t.id.value, t.numbersList(), t.matched, t.payout))
                sales += t.amount.value
            }
            (map as Map<Long, MutableList<TicketSlot>>) to sales
        }.getOrElse {
            log.error("lottery loadTickets failed", it)
            emptyMap<Long, MutableList<TicketSlot>>() to 0L
        }

    private fun snapshot(l: Lottery) = LotterySnapshot(
        id = l.id.value,
        status = l.status,
        startsAt = l.startsAt,
        endsAt = l.endsAt,
        seedPool = l.seedPool,
        serverSeed = l.serverSeed,
        serverSeedHash = l.serverSeedHash,
        tonSeed = l.tonSeed,
        winningNumbers = l.winningList(),
        drawnCount = l.drawnCount
    )

    private fun runtimeOf(snap: LotterySnapshot, sales: Long, status: Status, nextRevealAt: Instant) = Runtime(
        lotteryId = snap.id,
        status = status,
        startsAt = snap.startsAt,
        endsAt = snap.endsAt,
        seedPool = snap.seedPool,
        serverSeed = snap.serverSeed,
        serverSeedHash = snap.serverSeedHash,
        tonSeed = snap.tonSeed,
        winningNumbers = snap.winningNumbers,
        drawnCount = snap.drawnCount,
        salesPool = sales,
        nextRevealAt = nextRevealAt,
        settledPauseEndsAt = EPOCH0
    )

    // ── in-memory types ───────────────────────────────────────────────────────

    private class Runtime(
        val lotteryId: Long,
        var status: Status,
        val startsAt: Instant,
        val endsAt: Instant,
        val seedPool: Long,
        val serverSeed: String,
        val serverSeedHash: String,
        var tonSeed: String?,
        var winningNumbers: List<Int>,
        var drawnCount: Int,
        var salesPool: Long,
        var nextRevealAt: Instant,
        var settledPauseEndsAt: Instant
    )

    private class TicketSlot(val id: Long, val numbers: List<Int>, var won: Boolean?, var payout: Long?)

    private class TicketView(val id: Long, val numbers: List<Int>, val won: Boolean?, val payout: Long?)

    private class Shared(
        val prizePool: Long,
        val currency: String,
        val ticketPrice: Long,
        val startsAt: Long,
        val endsAt: Long,
        val winningNumbers: List<Int>,
        val bettingOpen: Boolean,
        val finished: Boolean,
        val status: String,
        val drawNonce: Long,
        val serverSeedHash: String,
        val serverSeed: String?,
        val tonSeed: String?
    )

    private class LotterySnapshot(
        val id: Long,
        val status: Status,
        val startsAt: Instant,
        val endsAt: Instant,
        val seedPool: Long,
        val serverSeed: String,
        val serverSeedHash: String,
        val tonSeed: String?,
        val winningNumbers: List<Int>,
        val drawnCount: Int
    )

    private class SettleItem(
        val ticketId: Long,
        val playerId: Long,
        val sessionToken: String,
        val roundId: Long,
        val isWinner: Boolean,
        val payout: Long
    )

    private class Outcome(val playerId: Long, val won: Boolean, val payout: Long)

    private class OrphanRec(val playerId: Long, val sessionToken: String, val isWinner: Boolean, val payout: Long)

    private sealed interface Action {
        data object Idle : Action
        data object StartDraw : Action
        data object OpenNext : Action
        class Reveal(val lotteryId: Long, val count: Int) : Action
        class BeginSettle(val lotteryId: Long) : Action
    }

    companion object {
        /** The lottery is TON-only; the connection currency is fixed to this. */
        val CURRENCY = Currency(Env.lotteryCurrency)

        private val GAME = LotteryTicket.GAME
        private val EPOCH0 = Instant.fromEpochMilliseconds(0)
        private const val BOOT_RETRY_MS = 2000L
        private val log = LoggerFactory.getLogger(LotteryEngine::class.java)
    }
}
