import type { Slot, SoundboardState } from "./types";

const SAMPLE_RATE = 44_100;
const MAX_SECONDS = 10 * 60;
const CHUNK_BYTES = 512 * 1024;

/**
 * Le plugin (Java) ne lit que le WAV : le navigateur décode le fichier (MP3, OGG, FLAC, M4A, WAV…) et le
 * réencode en WAV 16 bits, 44,1 kHz, mono ou stéréo.
 */
export async function toWav(file: File): Promise<{ wav: Blob; duration: number }> {
  let buffer: AudioBuffer;
  try {
    buffer = await new OfflineAudioContext(2, 1, SAMPLE_RATE).decodeAudioData(await file.arrayBuffer());
  } catch {
    throw new Error(`« ${file.name} » n'est pas un fichier audio lisible`);
  }
  if (buffer.duration > MAX_SECONDS) throw new Error(`« ${file.name} » dure plus de ${MAX_SECONDS / 60} minutes`);

  const channels = Math.min(2, buffer.numberOfChannels);
  const frames = buffer.length;
  const data = Array.from({ length: channels }, (_, c) => buffer.getChannelData(c));
  const out = new DataView(new ArrayBuffer(44 + frames * channels * 2));
  const text = (at: number, s: string) => [...s].forEach((ch, i) => out.setUint8(at + i, ch.charCodeAt(0)));
  text(0, "RIFF");
  out.setUint32(4, 36 + frames * channels * 2, true);
  text(8, "WAVE");
  text(12, "fmt ");
  out.setUint32(16, 16, true);
  out.setUint16(20, 1, true); // PCM
  out.setUint16(22, channels, true);
  out.setUint32(24, SAMPLE_RATE, true);
  out.setUint32(28, SAMPLE_RATE * channels * 2, true);
  out.setUint16(32, channels * 2, true);
  out.setUint16(34, 16, true);
  text(36, "data");
  out.setUint32(40, frames * channels * 2, true);
  let at = 44;
  for (let i = 0; i < frames; i++) {
    for (let c = 0; c < channels; c++) {
      const s = Math.max(-1, Math.min(1, data[c]![i]!));
      out.setInt16(at, s < 0 ? s * 0x8000 : s * 0x7fff, true);
      at += 2;
    }
  }
  return { wav: new Blob([out.buffer], { type: "audio/wav" }), duration: buffer.duration };
}

function base64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",", 2)[1] ?? "");
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

type Call = (action: string, data?: unknown) => Promise<SoundboardState | null>;

/** Envoie le fichier au plugin par morceaux ; [progress] reçoit 0..1. */
export async function uploadSound(file: File, slot: Slot, call: Call, progress: (p: number) => void): Promise<void> {
  progress(0);
  const { wav, duration } = await toWav(file);
  const count = Math.max(1, Math.ceil(wav.size / CHUNK_BYTES));
  for (let index = 0; index < count; index++) {
    const data = await base64(wav.slice(index * CHUNK_BYTES, (index + 1) * CHUNK_BYTES));
    const last = index === count - 1;
    const next = await call("upload", { ...slot, index, data, last, fileName: file.name, duration });
    if (!next) throw new Error(`envoi de « ${file.name} » interrompu`);
    progress((index + 1) / count);
  }
}
