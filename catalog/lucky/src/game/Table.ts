import * as THREE from 'three'
import { SCALE, COLORS, mat } from './theme'

const PLANK_SHADES = ['#83592f', '#6f4a28', '#7d542c', '#664420', '#8a5e34', '#734d27']

/**
 * The wooden plank table. Origin at the table CENTRE with the top surface on
 * y = 0, so every module placed at object.position.y === 0 rests on the planks.
 * Planks run along Z (front-to-back, toward the camera).
 */
export class Table {
  readonly object = new THREE.Group()

  private readonly geometries: THREE.BufferGeometry[] = []

  private readonly materials: THREE.Material[] = []

  private readonly texture: THREE.Texture

  constructor() {
    const w = SCALE.tableWidth
    const d = SCALE.tableDepth
    const t = SCALE.tableThickness

    this.texture = this.makeWoodTexture()

    const topMat = mat(COLORS.wood, { roughness: 0.78 })
    topMat.map = this.texture
    const sideMat = mat(COLORS.woodDark, { roughness: 0.85 })
    this.materials.push(topMat, sideMat)

    // BoxGeometry face order: +x, -x, +y(top), -y, +z, -z.
    const geo = new THREE.BoxGeometry(w, t, d)
    this.geometries.push(geo)
    const box = new THREE.Mesh(geo, [sideMat, sideMat, topMat, sideMat, sideMat, sideMat])
    box.position.y = -t / 2
    box.receiveShadow = true
    this.object.add(box)
  }

  dispose(): void {
    this.texture.dispose()
    for (const g of this.geometries) g.dispose()
    for (const m of this.materials) m.dispose()
  }

  private makeWoodTexture(): THREE.Texture {
    const canvas = document.createElement('canvas')
    canvas.width = 1024
    canvas.height = 1024
    const ctx = canvas.getContext('2d')!

    const planks = 6
    const pw = canvas.width / planks
    for (let i = 0; i < planks; i++) {
      const x = i * pw
      ctx.fillStyle = PLANK_SHADES[i % PLANK_SHADES.length]
      ctx.fillRect(x, 0, pw, canvas.height)

      // Wavy grain streaks running the length of the plank.
      for (let g = 0; g < 46; g++) {
        const gx = x + Math.sin(g * 13.3 + i) * pw * 0.02 + ((g * 97) % pw)
        const dark = (g + i) % 4 === 0
        ctx.strokeStyle = dark ? 'rgba(45,28,14,0.35)' : 'rgba(150,110,70,0.18)'
        ctx.lineWidth = dark ? 2 : 1
        ctx.beginPath()
        ctx.moveTo(gx, 0)
        for (let y = 0; y <= canvas.height; y += 28) {
          ctx.lineTo(gx + Math.sin(y * 0.03 + g) * 3, y)
        }
        ctx.stroke()
      }

      // Dark seam between planks.
      ctx.fillStyle = 'rgba(30,18,9,0.85)'
      ctx.fillRect(x - 2, 0, 4, canvas.height)
    }

    const tex = new THREE.CanvasTexture(canvas)
    tex.colorSpace = THREE.SRGBColorSpace
    tex.wrapS = THREE.RepeatWrapping
    tex.wrapT = THREE.RepeatWrapping
    tex.anisotropy = 8
    return tex
  }
}
