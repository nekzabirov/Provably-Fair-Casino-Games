// Procedural sound effects via the Web Audio API — no asset files. Mirrors the
// Mines client's approach: a lazily-created AudioContext (resumed on the first
// user gesture) routing every blip through a single master GainNode.

let ctx: AudioContext | null = null
let masterGain: GainNode | null = null

function audio(): AudioContext {
  if (!ctx) {
    const Ctor =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    ctx = new Ctor()
  }
  if (ctx.state === 'suspended') void ctx.resume()
  return ctx
}

function master(ac: AudioContext): GainNode {
  if (!masterGain || masterGain.context !== ac) {
    masterGain = ac.createGain()
    masterGain.gain.value = 1
    masterGain.connect(ac.destination)
  }
  return masterGain
}

interface BlipOptions {
  freq: number
  type?: OscillatorType
  dur?: number
  gain?: number
  slideTo?: number
  delay?: number
}

function blip(ac: AudioContext, o: BlipOptions): void {
  const { freq, type = 'triangle', dur = 0.14, gain = 0.16, slideTo, delay = 0 } = o
  const t0 = ac.currentTime + delay
  const osc = ac.createOscillator()
  const g = ac.createGain()
  osc.type = type
  osc.frequency.setValueAtTime(freq, t0)
  if (slideTo) osc.frequency.exponentialRampToValueAtTime(slideTo, t0 + dur)
  g.gain.setValueAtTime(0.0001, t0)
  g.gain.exponentialRampToValueAtTime(gain, t0 + 0.01)
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur)
  osc.connect(g).connect(master(ac))
  osc.start(t0)
  osc.stop(t0 + dur + 0.03)
}

/** Bright rising click when a number is picked; pitch walks UP a major
 *  pentatonic scale with the count, so filling a ticket literally climbs —
 *  every scale degree is consonant, no matter where the player stops.
 *  `count` = picks after this one. */
const PENTA = [0, 2, 4, 7, 9]

export function playPick(count = 1): void {
  const ac = audio()
  const i = Math.max(count - 1, 0)
  const oct = Math.min(Math.floor(i / PENTA.length), 2)
  const base = 523.25 * Math.pow(2, (PENTA[i % PENTA.length]! + 12 * oct) / 12) // C5 penta, climbing
  blip(ac, { freq: base, type: 'triangle', dur: 0.12, gain: 0.18, slideTo: base * 1.5 })
  blip(ac, { freq: base * 2, type: 'sine', dur: 0.08, gain: 0.06, delay: 0.004 })
}

/** Soft falling blip when a number is removed. */
export function playUnpick(): void {
  blip(audio(), { freq: 340, type: 'sine', dur: 0.1, gain: 0.12, slideTo: 210 })
}

/** Happy ascending chime when the ticket is complete (6/6) or auto-filled. */
export function playComplete(): void {
  const ac = audio()
  const notes = [659.25, 830.61, 987.77, 1318.5]
  notes.forEach((f, i) => blip(ac, { freq: f, type: 'triangle', dur: 0.18, gain: 0.14, delay: i * 0.06 }))
}

/** Purchase confirmed — a short major fanfare, warmer and longer than the chime. */
export function playBuy(): void {
  const ac = audio()
  const notes = [659.25, 783.99, 987.77, 1318.5]
  notes.forEach((f, i) => blip(ac, { freq: f, type: 'sine', dur: 0.24, gain: 0.16, delay: i * 0.09 }))
  blip(ac, { freq: 2637, type: 'sine', dur: 0.3, gain: 0.05, delay: 0.36 })
}

/** Rejected action (pick limit, failed buy) — a low, short thud. */
export function playDeny(): void {
  blip(audio(), { freq: 165, type: 'square', dur: 0.12, gain: 0.05, slideTo: 110 })
}

/** CLEAR — one falling swipe, the pick run in reverse. */
export function playClear(): void {
  blip(audio(), { freq: 620, type: 'triangle', dur: 0.18, gain: 0.1, slideTo: 210 })
}
