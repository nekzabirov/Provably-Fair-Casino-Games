// Procedural sound effects via the Web Audio API — Plinko ships zero audio
// assets. The AudioContext starts suspended; it is resumed on the first user
// gesture (DROP / tutorial), per browser autoplay rules.
//
// Everything routes through a single master GainNode, so the master mute
// silences it all in one move. Mute is persisted; muting before the first
// gesture is honored.

let ctx: AudioContext | null = null
let masterGain: GainNode | null = null

const MUTE_KEY = 'plinko.muted'

function loadMuted(): boolean {
  try {
    return localStorage.getItem(MUTE_KEY) === '1'
  } catch {
    return false
  }
}

let muted = loadMuted()

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

/** The single node everything connects to; mute sets its gain to 0. */
function master(ac: AudioContext): GainNode {
  if (!masterGain || masterGain.context !== ac) {
    masterGain = ac.createGain()
    masterGain.gain.value = muted ? 0 : 1
    masterGain.connect(ac.destination)
  }
  return masterGain
}

/** Call from a user gesture to unlock playback. */
export function unlockAudio(): void {
  audio()
}

export function isMuted(): boolean {
  return muted
}

/** Mute/unmute everything. Persists, and ramps the live master gain if present. */
export function setMuted(value: boolean): void {
  muted = value
  try {
    localStorage.setItem(MUTE_KEY, value ? '1' : '0')
  } catch {
    /* ignore */
  }
  if (masterGain) {
    const t = masterGain.context.currentTime
    masterGain.gain.cancelScheduledValues(t)
    masterGain.gain.setTargetAtTime(muted ? 0 : 1, t, 0.02)
  }
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
  const { freq, type = 'triangle', dur = 0.15, gain = 0.2, slideTo, delay = 0 } = o
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

/** Soft rising click when a ball is released. */
export function playDrop(): void {
  blip(audio(), { freq: 290, type: 'sine', dur: 0.1, gain: 0.12, slideTo: 430 })
}

/** Short woody tick on each peg hit; pitch wanders a little so runs feel alive. */
export function playPeg(row: number): void {
  const base = 740 + ((row * 53) % 160) - 80
  blip(audio(), { freq: base, type: 'square', dur: 0.035, gain: 0.05 })
}

/** Landing in a slot: tone follows the outcome. */
export function playLand(multiplier: number): void {
  const ac = audio()
  if (multiplier >= 10) {
    // Big win fanfare — ascending arpeggio.
    const notes = [523.25, 659.25, 783.99, 1046.5, 1318.5]
    notes.forEach((f, i) => blip(ac, { freq: f, type: 'triangle', dur: 0.22, gain: 0.18, delay: i * 0.07 }))
  } else if (multiplier >= 1) {
    // Win: a happy double chime.
    blip(ac, { freq: 659.25, type: 'triangle', dur: 0.16, gain: 0.16 })
    blip(ac, { freq: 987.77, type: 'sine', dur: 0.18, gain: 0.12, delay: 0.07 })
  } else {
    // Below stake: a muted low thunk.
    blip(ac, { freq: 196, type: 'sine', dur: 0.18, gain: 0.16, slideTo: 130 })
  }
}
