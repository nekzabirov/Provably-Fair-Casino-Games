// Procedural sound for TeleFly via the Web Audio API; background music is a
// streamed file. The AudioContext starts suspended and is resumed on the first
// user gesture (which also starts the music). Everything routes through one master
// GainNode, so the master mute silences engine, SFX, and music in one move.
//
// The flight "engine" is a continuous sawtooth + filtered-noise rush whose pitch
// and gain RISE with the live multiplier (updateEngine), so the plane sounds like
// it works harder the higher it climbs.

import { AMBIENT_URL } from './music'

let ctx: AudioContext | null = null
let masterGain: GainNode | null = null

const MUTE_KEY = 'telefly.muted'

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

/** Call from a user gesture to unlock playback and start the ambient bed. */
export function unlockAudio(): void {
  const ac = audio()
  startAmbient(ac)
  loadEngine(ac)
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

// ── flight engine ─────────────────────────────────────────────────────────────
// A real airplane prop loop (public/sfx/engine.ogg) whose playback rate + volume
// rise with the multiplier, so the plane audibly revs up. iOS/Safari can't decode
// ogg via Web Audio, so those fall back to a soft, low-passed sine — never harsh.

const ENGINE_URL = '/sfx/engine.ogg'

let engineSource: AudioBufferSourceNode | null = null // real prop loop
let engineOsc: OscillatorNode | null = null // gentle synth fallback
let engineGain: GainNode | null = null
let engineBuffer: AudioBuffer | null = null
let engineTried = false

/** Fetch + decode the prop loop once (decode fails on iOS ogg -> synth fallback). */
function loadEngine(ac: AudioContext): void {
  if (engineBuffer || engineTried) return
  engineTried = true
  fetch(ENGINE_URL)
    .then((r) => r.arrayBuffer())
    .then((b) => ac.decodeAudioData(b))
    .then((buf) => (engineBuffer = buf))
    .catch(() => {
      /* unsupported codec (iOS) — synth fallback is used */
    })
}

/** Start the engine (idempotent) plus a soft takeoff whoosh. Call at takeoff. */
export function startEngine(): void {
  if (engineSource || engineOsc) return
  const ac = audio()

  const gain = ac.createGain()
  gain.gain.setValueAtTime(0.0001, ac.currentTime)
  gain.connect(master(ac))
  engineGain = gain

  if (engineBuffer) {
    const src = ac.createBufferSource()
    src.buffer = engineBuffer
    src.loop = true
    src.playbackRate.value = 1.0
    src.connect(gain)
    src.start()
    engineSource = src
    gain.gain.exponentialRampToValueAtTime(0.5, ac.currentTime + 0.4)
  } else {
    // Soft, low-passed sine — pleasant, not the old harsh sawtooth.
    const osc = ac.createOscillator()
    osc.type = 'sine'
    osc.frequency.setValueAtTime(90, ac.currentTime)
    const lp = ac.createBiquadFilter()
    lp.type = 'lowpass'
    lp.frequency.value = 600
    osc.connect(lp).connect(gain)
    osc.start()
    engineOsc = osc
    gain.gain.exponentialRampToValueAtTime(0.06, ac.currentTime + 0.4)
  }

  loadEngine(ac) // ready for next round if not decoded yet
  blip(ac, { freq: 160, slideTo: 320, type: 'sine', dur: 0.5, gain: 0.08 })
}

/** Rev the engine with the live multiplier (glides, never steps). */
export function updateEngine(multiplier: number): void {
  if (!engineGain) return
  const ac = engineGain.context
  const climb = Math.log2(Math.max(1, multiplier))
  if (engineSource) {
    engineSource.playbackRate.setTargetAtTime(Math.min(1.9, 1 + 0.18 * climb), ac.currentTime, 0.12)
    engineGain.gain.setTargetAtTime(Math.min(0.75, 0.5 + 0.05 * climb), ac.currentTime, 0.12)
  } else if (engineOsc) {
    engineOsc.frequency.setTargetAtTime(Math.min(260, 90 * (1 + 0.5 * climb)), ac.currentTime, 0.12)
    engineGain.gain.setTargetAtTime(Math.min(0.12, 0.06 + 0.012 * climb), ac.currentTime, 0.12)
  }
}

/** Stop the engine (idempotent). Call at crash / cashout / teardown. */
export function stopEngine(): void {
  if (!engineGain) {
    engineSource = engineOsc = null
    return
  }
  const ac = engineGain.context
  const t = ac.currentTime
  engineGain.gain.cancelScheduledValues(t)
  engineGain.gain.setTargetAtTime(0.0001, t, 0.1)
  try {
    engineSource?.stop(t + 0.3)
    engineOsc?.stop(t + 0.3)
  } catch {
    /* already stopped */
  }
  engineSource = engineOsc = engineGain = null
}

/** Big explosion for a crash. */
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
// The track comes from music.ts; no track configured means no music.

const AMBIENT_LEVEL = 0.4

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
  out.gain.exponentialRampToValueAtTime(AMBIENT_LEVEL, ac.currentTime + 2.5)

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
      out.gain.setTargetAtTime(0, t, 0.3)
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
