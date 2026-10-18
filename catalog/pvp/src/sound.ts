// Procedural sound effects via the Web Audio API - PvP ships zero audio assets.
// The AudioContext starts suspended; it is resumed on the first user gesture.
// Everything routes through a single master GainNode, so mute silences it all.

let ctx: AudioContext | null = null
let masterGain: GainNode | null = null

const MUTE_KEY = 'pvp.muted'

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

function master(ac: AudioContext): GainNode {
  if (!masterGain || masterGain.context !== ac) {
    masterGain = ac.createGain()
    masterGain.gain.value = muted ? 0 : 1
    masterGain.connect(ac.destination)
  }
  return masterGain
}

export function unlockAudio(): void {
  audio()
}

export function isMuted(): boolean {
  return muted
}

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

/** War-drum thud + metal ring when a stake enters the arena. */
export function playBet(): void {
  const ac = audio()
  blip(ac, { freq: 150, type: 'sine', dur: 0.16, gain: 0.2, slideTo: 90 })
  blip(ac, { freq: 560, type: 'triangle', dur: 0.12, gain: 0.14, slideTo: 820 })
  blip(ac, { freq: 1180, type: 'sine', dur: 0.09, gain: 0.06, delay: 0.05 })
}

/** Tense drum hit each countdown second. */
export function playTick(): void {
  const ac = audio()
  blip(ac, { freq: 220, type: 'square', dur: 0.05, gain: 0.06, slideTo: 140 })
}

/** A heavy war-horn swell as the wheel launches into the strike. */
export function playSpin(): void {
  const ac = audio()
  blip(ac, { freq: 90, type: 'sawtooth', dur: 0.7, gain: 0.16, slideTo: 300 })
  blip(ac, { freq: 160, type: 'triangle', dur: 0.6, gain: 0.1, slideTo: 480, delay: 0.02 })
}

/** Metal strike as the pointer bites into a new territory. */
export function playClack(): void {
  const ac = audio()
  blip(ac, { freq: 1200, type: 'square', dur: 0.03, gain: 0.05, slideTo: 700 })
}

/** Triumphant brass fanfare for the victor. */
export function playWin(): void {
  const ac = audio()
  blip(ac, { freq: 110, type: 'sawtooth', dur: 0.5, gain: 0.12, slideTo: 220 })
  const notes = [523.25, 659.25, 783.99, 1046.5, 1318.5]
  notes.forEach((f, i) => blip(ac, { freq: f, type: 'triangle', dur: 0.26, gain: 0.19, delay: i * 0.08 }))
}

/** A defeated low horn when the pot slips away. */
export function playLose(): void {
  const ac = audio()
  blip(ac, { freq: 240, type: 'sine', dur: 0.5, gain: 0.16, slideTo: 90 })
  blip(ac, { freq: 180, type: 'sawtooth', dur: 0.45, gain: 0.08, slideTo: 70, delay: 0.06 })
}
