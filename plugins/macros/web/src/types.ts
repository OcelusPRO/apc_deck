/** État renvoyé par MacrosPlugin.state() (toutes les actions le renvoient). */
export interface Macro {
  id: string;
  name: string;
  description: string;
  shell: string;
  terminal: boolean;
  color: number;
  effect: "fixe" | "pulse" | "clignote";
  script: string;
  running: boolean;
}

export interface Slot {
  row: number;
  col: number;
  x: number;
  y: number;
}

export interface MacrosState {
  shells: { id: string; label: string }[];
  effects: Macro["effect"][];
  current: { row: number; col: number };
  macros: Macro[];
  slots: (Slot & { id: string })[];
  savedId?: string;
}
