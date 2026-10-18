// Procedural sound effects via the Web Audio API; the background music is a
// streamed audio file. The AudioContext starts suspended; it is resumed on the
// first user gesture (BET / cell click / tutorial), which also starts the music.
//
// Everything — every SFX and the music — routes through a single master GainNode,
// so the master mute silences both in one move (and kills in-flight sounds
// instantly). Mute is persisted; muting before the first gesture is honored.

import { AMBIENT_URL } from './music'

let ctx: AudioContext | null = null
let masterGain: GainNode | null = null

const MUTE_KEY = 'mines.muted'

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

/** Call from a user gesture to unlock playback and start the ambient bed. */
export function unlockAudio(): void {
  startAmbient(audio())
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
  // If the context isn't created yet, the next master() picks up `muted`.
  if (masterGain) {
    const t = masterGain.context.currentTime
    masterGain.gain.cancelScheduledValues(t)
    masterGain.gain.setTargetAtTime(muted ? 0 : 1, t, 0.02)
  }
  // Also mute the media element directly (covers the unrouted fallback path).
  if (ambientEl) ambientEl.muted = muted
}

export function toggleMuted(): void {
  setMuted(!muted)
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

/** Soft rising click when a round starts. */
export function playStart(): void {
  blip(audio(), { freq: 330, type: 'sine', dur: 0.12, gain: 0.14, slideTo: 494 })
}

/** Bright "ding" on a diamond; pitch climbs with the number of diamonds opened. */
export function playDiamond(step = 1): void {
  const ac = audio()
  const semis = Math.min(Math.max(step - 1, 0), 14)
  const base = 587.33 * Math.pow(2, semis / 12)
  blip(ac, { freq: base, type: 'triangle', dur: 0.16, gain: 0.2 })
  blip(ac, { freq: base * 2, type: 'sine', dur: 0.1, gain: 0.08, delay: 0.005 })
}

/** Explosion on a mine: a filtered noise burst plus a low falling thud. */
export function playMine(): void {
  const ac = audio()
  const dur = 0.55
  const buffer = ac.createBuffer(1, Math.floor(ac.sampleRate * dur), ac.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < data.length; i++) {
    data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / data.length, 2)
  }
  const noise = ac.createBufferSource()
  noise.buffer = buffer
  const lp = ac.createBiquadFilter()
  lp.type = 'lowpass'
  lp.frequency.setValueAtTime(2000, ac.currentTime)
  lp.frequency.exponentialRampToValueAtTime(120, ac.currentTime + dur)
  const g = ac.createGain()
  g.gain.setValueAtTime(0.5, ac.currentTime)
  g.gain.exponentialRampToValueAtTime(0.0001, ac.currentTime + dur)
  noise.connect(lp).connect(g).connect(master(ac))
  noise.start()
  noise.stop(ac.currentTime + dur + 0.05)
  blip(ac, { freq: 150, type: 'sine', dur: 0.4, gain: 0.4, slideTo: 45 })
}

/** Big, screen-filling explosion for hitting a mine (FAIL). */
export function playExplosion(): void {
  const ac = audio()
  const dur = 0.9
  const buffer = ac.createBuffer(1, Math.floor(ac.sampleRate * dur), ac.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < data.length; i++) {
    data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / data.length, 1.5)
  }
  const noise = ac.createBufferSource()
  noise.buffer = buffer
  const lp = ac.createBiquadFilter()
  lp.type = 'lowpass'
  lp.frequency.setValueAtTime(2600, ac.currentTime)
  lp.frequency.exponentialRampToValueAtTime(60, ac.currentTime + dur)
  const g = ac.createGain()
  g.gain.setValueAtTime(0.7, ac.currentTime)
  g.gain.exponentialRampToValueAtTime(0.0001, ac.currentTime + dur)
  noise.connect(lp).connect(g).connect(master(ac))
  noise.start()
  noise.stop(ac.currentTime + dur + 0.05)
  blip(ac, { freq: 120, type: 'sine', dur: 0.7, gain: 0.5, slideTo: 35 })
  blip(ac, { freq: 420, type: 'square', dur: 0.09, gain: 0.18, slideTo: 70 })
}

/** Happy ascending chime on cash out. */
export function playCashout(): void {
  const ac = audio()
  const notes = [523.25, 659.25, 783.99, 1046.5]
  notes.forEach((f, i) => blip(ac, { freq: f, type: 'triangle', dur: 0.2, gain: 0.16, delay: i * 0.075 }))
}

// ── background music — a streamed loop, started on the first gesture ──
// The track (see music.ts) routes through the master gain, so mute silences it
// with everything else. No track configured means no music.

const AMBIENT_LEVEL = 0.5

let ambient: { stop: () => void } | null = null
let ambientEl: HTMLAudioElement | null = null

function startAmbient(ac: AudioContext): void {
  const url = AMBIENT_URL
  if (ambient || !url) return

  const el = new Audio(url)
  el.loop = true
  el.muted = muted
  ambientEl = el

  const out = ac.createGain()
  out.gain.setValueAtTime(0.0001, ac.currentTime)
  out.gain.exponentialRampToValueAtTime(AMBIENT_LEVEL, ac.currentTime + 2.5) // gentle fade-in

  try {
    ac.createMediaElementSource(el).connect(out).connect(master(ac))
  } catch {
    // Web Audio routing unsupported — play unrouted; el.muted still honors mute.
  }

  void el.play().catch(() => {})

  ambient = {
    stop: () => {
      const t = ac.currentTime
      out.gain.cancelScheduledValues(t)
      out.gain.setTargetAtTime(0, t, 0.3) // fade out
      window.setTimeout(() => el.pause(), 700)
    },
  }
}

/** Fade out and stop the music (e.g. on unmount). */
export function stopAmbient(): void {
  ambient?.stop()
  ambient = null
  ambientEl = null
}
