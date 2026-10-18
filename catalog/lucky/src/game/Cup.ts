import * as THREE from 'three'
import { SCALE, COLORS, mat } from './theme'

/**
 * One shell-game cup: an inverted copper tumbler that covers the ball.
 *
 * Local origin sits at the BASE CENTRE on the table (y = 0). The opening faces
 * DOWN so the cup can hood the ball; the body rises to y = SCALE.cupHeight and
 * closes with a flat top. The scene positions/lifts the whole `object`.
 */
export class Cup {
  readonly object: THREE.Group

  private readonly bodyMaterial: THREE.MeshStandardMaterial

  private readonly geometries: THREE.BufferGeometry[] = []

  private readonly materials: THREE.Material[] = []

  constructor() {
    this.object = new THREE.Group()

    // Aged copper: metallic with a slightly rough, worn finish so the highlights
    // read as brushed metal rather than chrome. Double-sided for when it lifts.
    this.bodyMaterial = this.track(mat(COLORS.copper, { metalness: 1, roughness: 0.42 }))
    this.bodyMaterial.side = THREE.DoubleSide

    const profile = this.makeProfile()
    const bodyGeo = this.track(new THREE.LatheGeometry(profile, 56))
    const body = new THREE.Mesh(bodyGeo, this.bodyMaterial)
    body.castShadow = true
    body.receiveShadow = true
    this.object.add(body)

    // Darker rolled rim around the bottom opening.
    const rimMat = this.track(mat(COLORS.copperDark, { metalness: 1, roughness: 0.5 }))
    const rimTube = SCALE.cupBaseRadius * 0.09
    const rimGeo = this.track(new THREE.TorusGeometry(SCALE.cupBaseRadius - rimTube, rimTube, 18, 56))
    const rim = new THREE.Mesh(rimGeo, rimMat)
    rim.rotation.x = Math.PI / 2
    rim.castShadow = true
    this.object.add(rim)

    this.setHighlight(false)
  }

  setHighlight(on: boolean): void {
    this.bodyMaterial.emissive.set(on ? COLORS.copperLight : 0x000000)
    this.bodyMaterial.emissiveIntensity = on ? 0.4 : 0
  }

  dispose(): void {
    for (const geo of this.geometries) geo.dispose()
    for (const m of this.materials) m.dispose()
  }

  /** Open bottom rim, gently tapering wall, small chamfer into a flat top. */
  private makeProfile(): THREE.Vector2[] {
    const base = SCALE.cupBaseRadius
    const top = SCALE.cupTopRadius
    const height = SCALE.cupHeight

    const pts: THREE.Vector2[] = []
    pts.push(new THREE.Vector2(base, 0))

    const wallSteps = 10
    for (let i = 1; i <= wallSteps; i++) {
      const t = i / wallSteps
      const y = (height - 0.06) * t
      const r = base + (top - base) * t
      pts.push(new THREE.Vector2(r, y))
    }

    // Rounded shoulder + flat top closing on the axis.
    pts.push(new THREE.Vector2(top * 0.96, height - 0.02))
    pts.push(new THREE.Vector2(top * 0.7, height))
    pts.push(new THREE.Vector2(0, height))

    return pts
  }

  private track<T extends THREE.BufferGeometry>(geo: T): T
  private track<T extends THREE.Material>(material: T): T
  private track(asset: THREE.BufferGeometry | THREE.Material): THREE.BufferGeometry | THREE.Material {
    if (asset instanceof THREE.BufferGeometry) this.geometries.push(asset)
    else this.materials.push(asset)
    return asset
  }
}
