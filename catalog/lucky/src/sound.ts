// Procedural sound for the shell game - synthesized with the Web Audio API, no
// asset files. The AudioContext starts suspended; it is resumed (and the ambient
// bar-room pad started) on the first user gesture.
//
// Every effect and the ambient pad route through one master GainNode, so mute is
// a single move that also kills sounds already in flight. Mute is persisted, and
// muting before the first gesture is honored.

let ctx: AudioContext | null = null
let masterGain: GainNode | null = null

const MUTE_KEY = 'lucky.muted'

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
}

/** Call from a user gesture to unlock playback and start the ambient loop. */
export function unlockAudio(): void {
  startAmbient(audio())
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

interface NoiseOptions {
  dur?: number
  gain?: number
  from?: number // bandpass start Hz
  to?: number // bandpass end Hz
  delay?: number
}

function noise(ac: AudioContext, o: NoiseOptions = {}): void {
  const { dur = 0.18, gain = 0.18, from = 1800, to = 600, delay = 0 } = o
  const t0 = ac.currentTime + delay
  const buffer = ac.createBuffer(1, Math.max(1, Math.floor(ac.sampleRate * dur)), ac.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < data.length; i++) {
    data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / data.length, 1.5)
  }
  const src = ac.createBufferSource()
  src.buffer = buffer
  const bp = ac.createBiquadFilter()
  bp.type = 'bandpass'
  bp.Q.value = 0.8
  bp.frequency.setValueAtTime(from, t0)
  bp.frequency.exponentialRampToValueAtTime(Math.max(40, to), t0 + dur)
  const g = ac.createGain()
  g.gain.setValueAtTime(0.0001, t0)
  g.gain.exponentialRampToValueAtTime(gain, t0 + 0.012)
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur)
  src.connect(bp).connect(g).connect(master(ac))
  src.start(t0)
  src.stop(t0 + dur + 0.05)
}

/** A round opens - soft rising click on the PLAY tap. */
export function playStart(): void {
  blip(audio(), { freq: 330, type: 'sine', dur: 0.12, gain: 0.13, slideTo: 494 })
}

/** Bet stepper tick. */
export function playTick(): void {
  blip(audio(), { freq: 880, type: 'square', dur: 0.04, gain: 0.05 })
}

/** A cup is lifted - a short woody "tok". */
export function playLift(): void {
  const ac = audio()
  blip(ac, { freq: 260, type: 'triangle', dur: 0.1, gain: 0.16, slideTo: 170 })
  noise(ac, { dur: 0.05, gain: 0.06, from: 2600, to: 900 })
}

/** The ball is revealed under a cup - a lift plus a small shimmer. */
export function playReveal(): void {
  const ac = audio()
  playLift()
  blip(ac, { freq: 784, type: 'sine', dur: 0.22, gain: 0.09, slideTo: 1175, delay: 0.06 })
}

/** A cup is set back down - a low thunk. */
export function playCupDown(): void {
  const ac = audio()
  blip(ac, { freq: 180, type: 'sine', dur: 0.12, gain: 0.18, slideTo: 80 })
  noise(ac, { dur: 0.06, gain: 0.05, from: 900, to: 200 })
}

/** One shuffle swap - a soft whoosh with a clack at the end; `i` varies the pitch. */
export function playShuffle(i = 0): void {
  const ac = audio()
  const detune = 1 + ((i % 3) - 1) * 0.12
  noise(ac, { dur: 0.14, gain: 0.08, from: 1400 * detune, to: 500 })
  blip(ac, { freq: 150 * detune, type: 'triangle', dur: 0.05, gain: 0.07, delay: 0.1 })
}

/** Cups settled - a gentle two-note "ready" prompt. */
export function playReady(): void {
  const ac = audio()
  blip(ac, { freq: 523.25, type: 'sine', dur: 0.12, gain: 0.08 })
  blip(ac, { freq: 659.25, type: 'sine', dur: 0.14, gain: 0.08, delay: 0.09 })
}

/** Player selects a cup - a crisp click. */
export function playPick(): void {
  const ac = audio()
  blip(ac, { freq: 420, type: 'square', dur: 0.05, gain: 0.1, slideTo: 280 })
  blip(ac, { freq: 660, type: 'sine', dur: 0.09, gain: 0.07, delay: 0.01 })
}

/** Win - happy ascending chime. */
export function playWin(): void {
  const ac = audio()
  const notes = [523.25, 659.25, 783.99, 1046.5]
  notes.forEach((f, i) => blip(ac, { freq: f, type: 'triangle', dur: 0.22, gain: 0.16, delay: i * 0.08 }))
  blip(ac, { freq: 1046.5, type: 'sine', dur: 0.4, gain: 0.06, delay: 0.32 })
}

/** Lose - soft descending two-note tone. */
export function playLose(): void {
  const ac = audio()
  blip(ac, { freq: 392, type: 'triangle', dur: 0.22, gain: 0.13, slideTo: 370 })
  blip(ac, { freq: 294, type: 'triangle', dur: 0.36, gain: 0.13, slideTo: 233, delay: 0.18 })
}

// ── ambient bar-room pad - started on the first gesture, runs until stopAmbient ──

let ambient: { stop: () => void } | null = null

function startAmbient(ac: AudioContext): void {
  if (ambient) return

  const out = ac.createGain()
  out.gain.setValueAtTime(0.0001, ac.currentTime)
  out.gain.exponentialRampToValueAtTime(0.03, ac.currentTime + 3) // slow, gentle fade-in
  out.connect(master(ac))

  // Clean airy pad: pure sines forming a soft major chord, all in the midrange
  // (>= 220 Hz) - no sub-bass or sawtooth harmonics, so small speakers can't buzz.
  // The slightly detuned top voice adds a slow shimmer.
  const oscs = [
    { f: 220.0, g: 0.5 }, // A3
    { f: 277.18, g: 0.32 }, // C#4
    { f: 329.63, g: 0.28 }, // E4
    { f: 440.7, g: 0.12 }, // A4, detuned ~0.7 Hz against the 2nd harmonic for shimmer
  ].map(({ f, g }) => {
    const osc = ac.createOscillator()
    osc.type = 'sine'
    osc.frequency.value = f
    const og = ac.createGain()
    og.gain.value = g
    osc.connect(og).connect(out)
    osc.start()
    return osc
  })

  // Slow, shallow tremolo so the pad gently breathes (no filter sweep - pure
  // sines have nothing to sweep).
  const lfo = ac.createOscillator()
  lfo.type = 'sine'
  lfo.frequency.value = 0.08
  const lfoGain = ac.createGain()
  lfoGain.gain.value = 0.008
  lfo.connect(lfoGain).connect(out.gain)
  lfo.start()

  ambient = {
    stop: () => {
      const t = ac.currentTime
      out.gain.cancelScheduledValues(t)
      out.gain.setTargetAtTime(0, t, 0.15)
      const stopAt = t + 0.6
      oscs.forEach((o) => o.stop(stopAt))
      lfo.stop(stopAt)
    },
  }
}

/** Fade out and tear down the ambient loop (e.g. on unmount). */
export function stopAmbient(): void {
  ambient?.stop()
  ambient = null
}
