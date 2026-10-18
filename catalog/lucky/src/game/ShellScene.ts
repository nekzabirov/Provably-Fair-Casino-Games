import * as THREE from 'three'
import { Table } from './Table'
import { Cup } from './Cup'
import { Ball } from './Ball'
import { DogModel } from './DogModel'

/** Z of the cup row (toward the camera); the dog stands just behind it. */
export const CUP_Z = 0.5
const DOG_Z = -0.6

/**
 * Owns everything render-related: the WebGL context, camera, lights, environment
 * and the four mesh modules. Framed head-on (camera facing the character across
 * the table), lit by a dramatic top spotlight over a warm, blurred bar backdrop.
 */
export class ShellScene {
  readonly scene = new THREE.Scene()
  readonly camera: THREE.PerspectiveCamera
  readonly renderer: THREE.WebGLRenderer

  readonly table = new Table()
  readonly cups: [Cup, Cup, Cup]
  readonly ball = new Ball()
  readonly dog = new DogModel()

  private readonly raycaster = new THREE.Raycaster()
  private readonly pointer = new THREE.Vector2()
  private readonly resizeObserver: ResizeObserver
  private frameCb: ((dtMs: number) => void) | null = null
  private rafId = 0
  private lastTime = 0
  private disposed = false

  constructor(private readonly canvas: HTMLCanvasElement) {
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    this.renderer.shadowMap.enabled = true
    this.renderer.shadowMap.type = THREE.PCFShadowMap
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 0.98

    const env = this.makeEnvTexture()
    this.scene.background = env
    const pmrem = new THREE.PMREMGenerator(this.renderer)
    this.scene.environment = pmrem.fromEquirectangular(env).texture
    pmrem.dispose()
    this.scene.fog = new THREE.Fog(0x0c0805, 9, 22)

    this.camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100)

    this.addLights()

    this.cups = [new Cup(), new Cup(), new Cup()]
    this.cups.forEach((cup, i) => {
      cup.object.traverse((o) => (o.userData.cupIndex = i))
      this.scene.add(cup.object)
    })

    this.scene.add(this.table.object)
    this.scene.add(this.ball.object)
    this.dog.object.position.set(0, 0, DOG_Z)
    this.scene.add(this.dog.object)

    this.resizeObserver = new ResizeObserver(() => this.resize())
    this.resizeObserver.observe(canvas)
    this.resize()
  }

  onFrame(cb: (dtMs: number) => void): void {
    this.frameCb = cb
  }

  start(): void {
    this.lastTime = performance.now()
    const loop = (now: number) => {
      if (this.disposed) return
      const dt = Math.min(50, now - this.lastTime)
      this.lastTime = now
      this.frameCb?.(dt)
      this.renderer.render(this.scene, this.camera)
      this.rafId = requestAnimationFrame(loop)
    }
    this.rafId = requestAnimationFrame(loop)
  }

  /** Raycast a screen point against the cups; returns the cup identity index or null. */
  pickCup(clientX: number, clientY: number): number | null {
    const rect = this.canvas.getBoundingClientRect()
    this.pointer.set(
      ((clientX - rect.left) / rect.width) * 2 - 1,
      -((clientY - rect.top) / rect.height) * 2 + 1,
    )
    this.raycaster.setFromCamera(this.pointer, this.camera)
    const hits = this.raycaster.intersectObjects(
      this.cups.map((c) => c.object),
      true,
    )
    const idx = hits[0]?.object.userData.cupIndex
    return typeof idx === 'number' ? idx : null
  }

  /** Equirectangular warm-bar texture: backdrop + reflection source for the copper. */
  private makeEnvTexture(): THREE.Texture {
    const canvas = document.createElement('canvas')
    canvas.width = 1024
    canvas.height = 512
    const ctx = canvas.getContext('2d')!

    const grad = ctx.createLinearGradient(0, 0, 0, 512)
    grad.addColorStop(0, '#46331f')
    grad.addColorStop(0.4, '#271a12')
    grad.addColorStop(1, '#0a0705')
    ctx.fillStyle = grad
    ctx.fillRect(0, 0, 1024, 512)

    // Warm bokeh — hanging lamps and backlit bottles, kept soft so it reads blurred.
    const blobs = 26
    for (let i = 0; i < blobs; i++) {
      const x = ((i * 197) % 1024) + Math.sin(i) * 30
      const y = 120 + ((i * 53) % 230)
      const r = 30 + (i % 5) * 22
      const warm = i % 3 === 0 ? '255,210,140' : i % 3 === 1 ? '230,150,80' : '255,180,110'
      const g = ctx.createRadialGradient(x, y, 0, x, y, r)
      g.addColorStop(0, `rgba(${warm},${0.5 - (i % 4) * 0.07})`)
      g.addColorStop(1, 'rgba(0,0,0,0)')
      ctx.fillStyle = g
      ctx.fillRect(x - r, y - r, r * 2, r * 2)
    }

    // Bright ceiling glow band near the top.
    const ceil = ctx.createLinearGradient(0, 0, 0, 120)
    ceil.addColorStop(0, 'rgba(255,225,170,0.5)')
    ceil.addColorStop(1, 'rgba(255,225,170,0)')
    ctx.fillStyle = ceil
    ctx.fillRect(0, 0, 1024, 120)

    const tex = new THREE.CanvasTexture(canvas)
    tex.colorSpace = THREE.SRGBColorSpace
    tex.mapping = THREE.EquirectangularReflectionMapping
    return tex
  }

  private addLights(): void {
    // Dramatic warm key spotlight from above-front, casting the soft shadow.
    const key = new THREE.SpotLight(0xfff1d6, 85, 24, Math.PI / 4.5, 0.55, 1.4)
    key.position.set(0.6, 7.5, 3.6)
    key.target.position.set(0, 1.4, -0.4)
    key.castShadow = true
    key.shadow.mapSize.set(2048, 2048)
    key.shadow.bias = -0.0004
    key.shadow.camera.near = 1
    key.shadow.camera.far = 22
    this.scene.add(key)
    this.scene.add(key.target)

    // Gentle warm fill from the camera side so the dark cloak isn't crushed.
    const fill = new THREE.DirectionalLight(0xffe9c8, 0.85)
    fill.position.set(0, 2.5, 7)
    this.scene.add(fill)

    // Cool rim from behind to edge-light the black hoodie off the dark backdrop.
    const rim = new THREE.DirectionalLight(0x9fbfe0, 2.1)
    rim.position.set(-3.5, 4.5, -5)
    this.scene.add(rim)

    const rim2 = new THREE.DirectionalLight(0xbfd2ee, 1.4)
    rim2.position.set(3.5, 4, -5)
    this.scene.add(rim2)

    this.scene.add(new THREE.HemisphereLight(0x5a4632, 0x0a0705, 0.35))
  }

  private resize(): void {
    const w = this.canvas.clientWidth || window.innerWidth
    const h = this.canvas.clientHeight || window.innerHeight
    this.renderer.setSize(w, h, false)
    this.camera.aspect = w / h
    this.frameCamera(w / h)
  }

  private frameCamera(aspect: number): void {
    const portrait = aspect < 1
    // Head-on: eye-level in front of the dog, cups in the lower foreground.
    const dist = portrait ? 5.6 + (1 / Math.max(aspect, 0.4) - 1) * 1.9 : 5.0
    this.camera.fov = portrait ? 46 : 40
    this.camera.position.set(0, 2.05, dist)
    this.camera.lookAt(0, 1.45, -0.2)
    this.camera.updateProjectionMatrix()
  }

  dispose(): void {
    this.disposed = true
    cancelAnimationFrame(this.rafId)
    this.resizeObserver.disconnect()
    this.scene.environment?.dispose()
    this.scene.background instanceof THREE.Texture && this.scene.background.dispose()
    this.table.dispose()
    this.cups.forEach((c) => c.dispose())
    this.ball.dispose()
    this.dog.dispose()
    this.renderer.dispose()
  }
}
