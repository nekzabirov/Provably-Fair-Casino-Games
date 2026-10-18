import * as THREE from 'three'
import { SCALE, COLORS, mat } from './theme'

export class Ball {
  readonly object: THREE.Group

  private readonly geometries: THREE.BufferGeometry[] = []

  private readonly materials: THREE.Material[] = []

  constructor() {
    this.object = new THREE.Group()

    const sphereGeo = new THREE.SphereGeometry(SCALE.ballRadius, 32, 24)
    const sphereMat = mat(COLORS.ball, {
      metalness: 0.9,
      roughness: 0.3,
      emissive: COLORS.ballEmissive,
      emissiveIntensity: 0.4,
    })
    const sphere = new THREE.Mesh(sphereGeo, sphereMat)
    // Origin is the table-contact point, so lift the sphere by its radius.
    sphere.position.y = SCALE.ballRadius
    sphere.castShadow = true
    this.geometries.push(sphereGeo)
    this.materials.push(sphereMat)
    this.object.add(sphere)
  }

  dispose(): void {
    for (const geo of this.geometries) geo.dispose()
    for (const mat of this.materials) mat.dispose()
  }
}
