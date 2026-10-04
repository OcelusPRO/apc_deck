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
  playing: boolean;
  hasFile: boolean;
}

export interface SoundboardState {
  current: { row: number; col: number };
  sounds: Sound[];
}

export const sameSlot = (a: Slot, b: Slot) => a.row === b.row && a.col === b.col && a.x === b.x && a.y === b.y;
export const slotKey = (s: Slot) => `${s.row}.${s.col}.${s.x}.${s.y}`;
