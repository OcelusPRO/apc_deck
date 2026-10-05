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

/** Plugin dont le dépôt GitHub publie une version plus récente. */
export interface PluginUpdate {
  id: string;
  name: string;
  current: string;
  latest: string;
  pageUrl: string | null;
  installing: boolean;
  error: string | null;
}

/** Recherche de mises à jour (Updater côté cœur) ; status DISABLED depuis les sources. */
export interface Update {
  current: string | null;
  status: "DISABLED" | "IDLE" | "CHECKING" | "UP_TO_DATE" | "AVAILABLE" | "DOWNLOADING" | "INSTALLING" | "ERROR";
  latest: string | null;
  pageUrl: string | null;
  assetName: string | null;
  notes: string | null;
  progress: number | null;
  error: string | null;
  checkedAt: number | null;
  plugins: PluginUpdate[];
}

/** Mobile autorisé à piloter ce PC. */
export interface RemoteDevice {
  fingerprint: string;
  name: string;
  pairedAt: number;
  lastSeen: number;
  connected: boolean;
  address: string | null;
}

/** PC appairé à cet appareil. */
export interface RemoteServerEntry {
  fingerprint: string;
  name: string;
  hosts: string[];
  port: number;
  pairedAt: number;
}

/** Contrôle à distance : ce PC piloté par des mobiles (server), cet appareil pilotant un PC (client). */
export interface Remote {
  deviceName: string;
  server: {
    enabled: boolean;
    running: boolean;
    port: number;
    error: string | null;
    fingerprint: string;
    pairing: { link: string; hosts: string[]; qrSize: number; qrPath: string; expiresAt: number } | null;
    devices: RemoteDevice[];
  };
  client: {
    state: "IDLE" | "CONNECTING" | "CONNECTED" | "ERROR";
    server: string | null;
    serverName: string | null;
    address: string | null;
    error: string | null;
    servers: RemoteServerEntry[];
  };
}

export interface AppState {
  plugins: PluginView[];
  /** virtual : l'APC virtuel remplace l'appareil (activé, et aucun APC réel branché). */
  device: { connected: boolean; detail: string; virtual?: boolean };
  /** virtual : APC virtuel activé (réglage, même quand un APC réel est branché). */
  settings: { mode: string; knobs: string; virtual?: boolean; modes: string[]; platform?: "desktop" | "android" } | null;
  leds: Leds | null;
  input: Input | null;
  learning: { pluginId: string; fieldKey: string } | null;
  pager: Pager | null;
  update: Update | null;
  remote: Remote | null;
  logs: LogLine[];
}
