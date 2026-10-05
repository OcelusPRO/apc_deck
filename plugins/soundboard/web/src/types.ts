/** État renvoyé par SoundboardPlugin.state() (toutes les actions le renvoient). */
export interface Slot {
  row: number;
  col: number;
  x: number;
  y: number;
}

export interface Sound extends Slot {
  id: string;
  name: string;
  color: number;
  volume: number;
  fileName: string;
  duration: number;
  /** Plage jouée, en secondes ; end = 0 : jusqu'à la fin du fichier. */
  start: number;
  end: number;
  /** En lecture ou en pause. */
  playing: boolean;
  paused: boolean;
  hasFile: boolean;
}

export interface SoundboardState {
  current: { row: number; col: number };
  /** Volume général, 0..200 %. */
  master: number;
  /** Potar qui règle le volume général (libellé). */
  masterKnob: string;
  sounds: Sound[];
}

export const sameSlot = (a: Slot, b: Slot) => a.row === b.row && a.col === b.col && a.x === b.x && a.y === b.y;
export const slotKey = (s: Slot) => `${s.row}.${s.col}.${s.x}.${s.y}`;

export const MAX_VOLUME = 200;
