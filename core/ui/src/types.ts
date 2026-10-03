/** Événements poussés par l'AppBridge (cœur Kotlin) sur /<jeton>/app/events. */
export type FieldType = "bool" | "int" | "decimal" | "text" | "choice" | "color" | "binding";

export interface ConfigField {
  key: string;
  label: string;
  description: string;
  type: FieldType;
  min?: number;
  max?: number;
  multiline?: boolean;
  options?: string[];
  accepts?: string[];
}

export interface BindingValue {
  encoded: string;
  label: string;
}

export interface PluginView {
  id: string;
  name: string;
  version: string;
  author: string;
  description: string;
  color: number;
  status: "ENABLED" | "DISABLED" | "ERROR";
  error: string | null;
  builtin: boolean;
  manager: boolean;
  listening: boolean;
  foreground: boolean;
  paused: boolean;
  webUrl: string | null;
  fields: ConfigField[];
  config: Record<string, boolean | number | string | BindingValue>;
}

export interface Leds {
  colors: number[];
  effects: number[];
  buttons: Record<string, number>;
}

export interface Input {
  pads: number[];
  buttons: string[];
  notes: number[];
  knobs: number[];
}

export interface PagerSlot {
  row: number;
  col: number;
  x: number;
  y: number;
}

export interface Pager {
  current: { row: number; col: number };
  slots: (PagerSlot & { id: string })[];
}

export interface LogLine {
  text: string;
  level: "DEBUG" | "INFO" | "WARN" | "ERROR";
}

export interface AppState {
  plugins: PluginView[];
  device: { connected: boolean; detail: string };
  settings: { mode: string; knobs: string; modes: string[] } | null;
  leds: Leds | null;
  input: Input | null;
  learning: { pluginId: string; fieldKey: string } | null;
  pager: Pager | null;
  logs: LogLine[];
}
