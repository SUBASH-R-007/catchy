/** Small deterministic PRNG (mulberry32) so the mock data is reproducible for a given seed. */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export class Rng {
  private readonly next: () => number;

  constructor(seed: number) {
    this.next = mulberry32(seed);
  }

  /** Uniform float in [0, 1). */
  float(): number {
    return this.next();
  }

  /** Uniform integer in [min, max] (inclusive). */
  int(min: number, max: number): number {
    return min + Math.floor(this.next() * (max - min + 1));
  }

  /** Uniform float in [min, max). */
  between(min: number, max: number): number {
    return min + this.next() * (max - min);
  }

  chance(probability: number): boolean {
    return this.next() < probability;
  }

  pick<T>(items: readonly T[]): T {
    return items[Math.floor(this.next() * items.length)] as T;
  }

  /** Approximately standard-normal noise (sum of uniforms). */
  gauss(): number {
    return (this.next() + this.next() + this.next() + this.next() - 2) * 1.7320508;
  }

  /** Lower-case hex string of `length` characters. */
  hex(length: number): string {
    let out = '';
    while (out.length < length) out += Math.floor(this.next() * 0x10000).toString(16).padStart(4, '0');
    return out.slice(0, length);
  }
}

/** 32-bit FNV-1a, used to derive stable fake key fingerprints from synthetic keys. */
export function fnv1a(text: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

/** `sha256:<16 hex>` style fingerprint for a synthetic key (not a real hash — mock only). */
export function fakeFingerprint(key: string): string {
  const a = fnv1a(key).toString(16).padStart(8, '0');
  const b = fnv1a(`${key}#salt`).toString(16).padStart(8, '0');
  return `sha256:${a}${b}`;
}
