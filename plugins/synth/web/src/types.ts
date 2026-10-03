/** État renvoyé par SynthPlugin.state() (toutes les actions le renvoient). */
export interface Option {
  id: string;
  label: string;
}

export interface Knob {
  name: string;
  label: string;
  knob: number;
  value: number;
  display: string;
}

export interface Preset {
  saved: boolean;
  active: boolean;
  waveform: string | null;
}

export interface Song {
  name: string | null;
  playing: boolean;
  seconds: number | null;
  notes: number | null;
}

export interface SynthState {
  foreground: boolean;
  audio: boolean;
  audioError: string | null;
  waveform: string;
  waveforms: Option[];
  transpose: number;
  knobs: Knob[];
  toggles: { arp: boolean; latch: boolean; mono: boolean; echo: boolean; vibrato: boolean };
  arpMode: string;
  arpModes: Option[];
  arpRate: string;
  arpRates: Option[];
  chord: string;
  chords: Option[];
  bpm: number;
  bpmRange: [number, number];
  presets: Preset[];
  notes: number[];
  songs: Song[];
  songLoop: boolean;
}
