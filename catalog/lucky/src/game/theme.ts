import * as THREE from 'three'

/**
 * Shared world contract for every mesh module (Table, Cup, Ball, the mascot).
 *
 * Conventions all modules MUST follow:
 *  - World units are "table units". The table top surface is the plane y = 0.
 *    Cups, ball and the dog all rest their base/feet on y = 0.
 *  - Each module builds its geometry around its OWN local origin and exposes a
 *    `THREE.Group` in `.object`. The scene places it via `object.position`.
 *  - Everything faces +Z (toward the camera / player).
 *  - Solid meshes set `castShadow = true`; the table top sets `receiveShadow`.
 *
 * Materials are physically-based (MeshStandardMaterial) for the soft, glossy
 * "clay" look of the reference render; the scene supplies an environment map so
 * metals (the copper cups) have something to reflect.
 */
export const SCALE = {
  tableWidth: 5.6, // X extent of the plank table top
  tableDepth: 5.0, // Z extent — runs from behind the dog out toward the camera
  tableThickness: 0.5,
  cupHeight: 0.82,
  cupBaseRadius: 0.37, // radius at the open (bottom) rim that sits on the table
  cupTopRadius: 0.24, // radius near the closed (top) — copper cups taper only slightly
  cupSpacing: 0.96, // X distance between adjacent cup centres
  ballRadius: 0.14,
  liftHeight: 0.95, // how high a cup rises on reveal / pick
} as const

/** The three slot X positions, left → right. All slots sit on the row z = 0. */
export const SLOT_X: readonly [number, number, number] = [-SCALE.cupSpacing, 0, SCALE.cupSpacing]

export const COLORS = {
  copper: 0xb06a3c,
  copperDark: 0x744220,
  copperLight: 0xd98f54,
  wood: 0x7a5230,
  woodDark: 0x4a3018,
  woodLight: 0x9c6e42,
  ball: 0xe6b94e,
  ballEmissive: 0x5a3c00,
  cloak: 0x202024, // black hoodie — body + sleeves
  cloakLight: 0x303036,
  hood: 0x2a2a30, // black hood (a touch lighter so the top catches the spotlight)
  hoodShade: 0x0d0d10, // near-black hood interior framing the white face
  face: 0xefe8db, // cream muzzle
  eye: 0x1e1f24, // dark eye mask
  nose: 0x0d0d0d, // glossy black nose
  hand: 0xefe8db, // white hands
} as const

interface MatOpts {
  roughness?: number
  metalness?: number
  emissive?: number
  emissiveIntensity?: number
  transparent?: boolean
  opacity?: number
}

/** Physically-based material factory. Every module colours its meshes through this. */
export function mat(color: number, opts: MatOpts = {}): THREE.MeshStandardMaterial {
  return new THREE.MeshStandardMaterial({
    color,
    roughness: opts.roughness ?? 0.72,
    metalness: opts.metalness ?? 0,
    emissive: new THREE.Color(opts.emissive ?? 0x000000),
    emissiveIntensity: opts.emissiveIntensity ?? 1,
    transparent: opts.transparent ?? false,
    opacity: opts.opacity ?? 1,
  })
}
