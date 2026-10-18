import * as THREE from 'three'
import { mat } from './theme'

/**
 * The dealer mascot: a friendly cartoon puppy in a bow tie, built entirely from
 * Three.js primitives (no model file to download). Feet on local y = 0, facing +Z.
 *
 * Exposes the pivot API the game animates: `head`, both ears (children of the head,
 * so they follow a nod) and both arms. Gestures rotate a pivot's pitch (.x) as an
 * offset from its rest pose; the whole-body bob moves `object`.
 */
const FUR = 0xc98b4f
const FUR_DARK = 0x8a5a2e
const CREAM = 0xf3e6cf
const NOSE = 0x141414
const EYE = 0x1b1b1f
const BOW = 0xb3202a

const BODY_RADIUS = 0.75
const BODY_LENGTH = 0.9
const BODY_Y = 1.3
const NECK_Y = 2.45
const HEAD_RADIUS = 0.72
const SHOULDER_X = 0.78
const SHOULDER_Y = 2.05
const ARM_REST_PITCH = -0.35
const ARM_REST_SPLAY = 0.15
const EAR_REST_SPLAY = 0.25

export class DogModel {
  readonly object = new THREE.Group()

  readonly head = new THREE.Group()

  readonly leftEar = new THREE.Group()

  readonly rightEar = new THREE.Group()

  readonly leftArm = new THREE.Group()

  readonly rightArm = new THREE.Group()

  private readonly geometries: THREE.BufferGeometry[] = []

  private readonly materials: THREE.Material[] = []

  private readonly matFur = this.track(mat(FUR, { roughness: 0.85 }))

  private readonly matFurDark = this.track(mat(FUR_DARK, { roughness: 0.88 }))

  private readonly matCream = this.track(mat(CREAM, { roughness: 0.7 }))

  private readonly matNose = this.track(mat(NOSE, { roughness: 0.2 }))

  private readonly matEye = this.track(mat(EYE, { roughness: 0.3 }))

  private readonly matShine = this.track(mat(0xffffff, { roughness: 0.1, emissive: 0xffffff, emissiveIntensity: 0.6 }))

  private readonly matBow = this.track(mat(BOW, { roughness: 0.5 }))

  constructor() {
    this.buildBody()
    this.buildHead()
    this.buildEars()
    this.buildArms()
    this.object.traverse((o) => {
      const mesh = o as THREE.Mesh
      if (mesh.isMesh) {
        mesh.castShadow = true
        mesh.receiveShadow = true
      }
    })
  }

  private buildBody(): void {
    const body = this.mesh(new THREE.CapsuleGeometry(BODY_RADIUS, BODY_LENGTH, 8, 24), this.matFur)
    body.position.y = BODY_Y
    body.scale.set(1, 1, 0.85)

    const belly = this.mesh(new THREE.SphereGeometry(0.55, 24, 16), this.matCream)
    belly.position.set(0, BODY_Y - 0.05, 0.42)
    belly.scale.set(0.95, 1.25, 0.5)

    const knotGeo = new THREE.SphereGeometry(0.1, 16, 12)
    const wingGeo = new THREE.ConeGeometry(0.16, 0.32, 16)
    const knot = this.mesh(knotGeo, this.matBow)
    knot.position.set(0, NECK_Y - 0.12, 0.58)
    const leftWing = this.mesh(wingGeo, this.matBow)
    leftWing.position.set(-0.2, NECK_Y - 0.12, 0.56)
    leftWing.rotation.z = Math.PI / 2
    const rightWing = this.mesh(wingGeo, this.matBow)
    rightWing.position.set(0.2, NECK_Y - 0.12, 0.56)
    rightWing.rotation.z = -Math.PI / 2

    this.object.add(body, belly, knot, leftWing, rightWing)
  }

  private buildHead(): void {
    this.head.position.set(0, NECK_Y, 0.05)
    this.object.add(this.head)

    const skull = this.mesh(new THREE.SphereGeometry(HEAD_RADIUS, 32, 24), this.matFur)
    skull.position.y = 0.62

    const snout = this.mesh(new THREE.SphereGeometry(0.4, 24, 16), this.matCream)
    snout.position.set(0, 0.42, 0.58)
    snout.scale.set(1.05, 0.8, 0.95)

    const nose = this.mesh(new THREE.SphereGeometry(0.13, 20, 14), this.matNose)
    nose.position.set(0, 0.58, 0.95)
    nose.scale.set(1.25, 0.9, 1)

    const eyeGeo = new THREE.SphereGeometry(0.09, 16, 12)
    const shineGeo = new THREE.SphereGeometry(0.03, 8, 6)
    for (const side of [-1, 1]) {
      const eye = this.mesh(eyeGeo, this.matEye)
      eye.position.set(side * 0.26, 0.82, 0.6)
      const shine = this.mesh(shineGeo, this.matShine)
      shine.position.set(side * 0.26 + 0.03, 0.86, 0.68)
      this.head.add(eye, shine)
    }

    this.head.add(skull, snout, nose)
  }

  private buildEars(): void {
    const earGeo = new THREE.SphereGeometry(0.24, 20, 14)
    const ears: [THREE.Group, number][] = [
      [this.leftEar, -1],
      [this.rightEar, 1],
    ]
    for (const [pivot, side] of ears) {
      pivot.position.set(side * 0.58, 1.05, 0)
      pivot.rotation.z = side * EAR_REST_SPLAY
      const ear = this.mesh(earGeo, this.matFurDark)
      ear.position.y = -0.42
      ear.scale.set(0.85, 2, 0.4)
      pivot.add(ear)
      this.head.add(pivot)
    }
  }

  private buildArms(): void {
    const armGeo = new THREE.CapsuleGeometry(0.2, 0.75, 6, 16)
    const pawGeo = new THREE.SphereGeometry(0.25, 20, 14)
    const arms: [THREE.Group, number][] = [
      [this.leftArm, -1],
      [this.rightArm, 1],
    ]
    for (const [pivot, side] of arms) {
      pivot.position.set(side * SHOULDER_X, SHOULDER_Y, 0.1)
      pivot.rotation.set(ARM_REST_PITCH, 0, side * ARM_REST_SPLAY)
      const arm = this.mesh(armGeo, this.matFur)
      arm.position.y = -0.55
      const paw = this.mesh(pawGeo, this.matCream)
      paw.position.y = -1.05
      pivot.add(arm, paw)
      this.object.add(pivot)
    }
  }

  private mesh(geometry: THREE.BufferGeometry, material: THREE.Material): THREE.Mesh {
    if (!this.geometries.includes(geometry)) this.geometries.push(geometry)
    return new THREE.Mesh(geometry, material)
  }

  private track<T extends THREE.Material>(material: T): T {
    this.materials.push(material)
    return material
  }

  dispose(): void {
    for (const geometry of this.geometries) geometry.dispose()
    for (const material of this.materials) material.dispose()
  }
}
