import { useEffect, useRef, useState, type ReactNode } from "react";
import { APC_PALETTE, Button, Slider, apcdeck, cx, padHex, usePluginEvent } from "@apcdeck/web";
import { Waveform, formatTime } from "./Waveform";
import { MAX_VOLUME, type Slot, type Sound, type SoundboardState } from "./types";

type Call = (action: string, data?: unknown) => Promise<SoundboardState | null>;
type Draft = Pick<Sound, "name" | "color" | "volume" | "start" | "end">;

/** Écoute en cours depuis l'éditeur (événement "preview" du plugin). */
interface Preview {
  id?: string;
  from?: number;
  to?: number;
  playing: boolean;
  paused: boolean;
  run: number;
}

/** Durée écoutée en fin de sélection quand on déplace la poignée de fin. */
const END_PREVIEW = 2;

const FILE_TYPES = "audio/*,.mp3,.wav,.ogg,.flac,.m4a,.aac,.opus,.webm";

/**
 * Éditeur d'un pad, en fenêtre modale. Rien n'est envoyé avant « Enregistrer » ; la plage hors sélection est
 * alors supprimée du fichier. Écouter ne touche pas aux pads (lecture séparée, au volume du brouillon).
 */
export function SoundEditor({
  slot,
  sound,
  call,
  progress,
  upload,
  onClose,
}: {
  slot: Slot;
  sound: Sound | null;
  call: Call;
  progress: number | undefined;
  upload: (file: File, slot: Slot) => Promise<void>;
  onClose: () => void;
}) {
  const fileInput = useRef<HTMLInputElement>(null);
  const picker = (
    <input
      ref={fileInput}
      type="file"
      accept={FILE_TYPES}
      className="hidden"
      onChange={(e) => {
        const file = e.target.files?.[0];
        e.target.value = "";
        if (file) void upload(file, slot);
      }}
    />
  );
  const uploading = progress !== undefined;
  const uploadLabel = !uploading ? null : progress === 0 ? "Conversion…" : `Envoi ${Math.round(progress * 100)} %`;

  if (!sound) {
    return (
      <Modal onClose={onClose} narrow>
        {picker}
        <Header title="Pad vide" slot={slot} onClose={onClose} />
        <p className="text-muted">Choisis un fichier audio (MP3, WAV, OGG, FLAC, M4A…) ou glisse-le directement sur le pad.</p>
        <div>
          <Button variant="primary" disabled={uploading} onClick={() => fileInput.current?.click()}>
            {uploadLabel ?? "Choisir un fichier audio"}
          </Button>
        </div>
      </Modal>
    );
  }
  return (
    <Editor sound={sound} slot={slot} call={call} onClose={onClose} uploadLabel={uploadLabel} pickFile={() => fileInput.current?.click()}>
      {picker}
    </Editor>
  );
}

function Editor({
  sound,
  slot,
  call,
  onClose,
  uploadLabel,
  pickFile,
  children,
}: {
  sound: Sound;
  slot: Slot;
  call: Call;
  onClose: () => void;
  uploadLabel: string | null;
  pickFile: () => void;
  children: ReactNode;
}) {
  const initial: Draft = { name: sound.name, color: sound.color, volume: sound.volume, start: sound.start, end: sound.end };
  const [draft, setDraft] = useState<Draft>(initial);
  const [wave, setWave] = useState<{ peaks: number[]; duration: number } | null>(null);
  const [saving, setSaving] = useState(false);
  const preview = usePreview(sound.id);
  const playhead = usePlayhead(preview);

  useEffect(() => {
    if (!sound.hasFile) return;
    void apcdeck().call<{ peaks: number[]; duration: number } | null>("peaks", slot).then((w) => w && setWave(w));
    return () => void apcdeck().call("previewStop");
  }, []);

  const duration = wave?.duration ?? sound.duration;
  const to = draft.end > 0 ? Math.min(draft.end, duration) : duration;
  const dirty = (Object.keys(initial) as (keyof Draft)[]).some((k) => initial[k] !== draft[k]);
  const removed = duration - (to - draft.start);
  const color = padHex(draft.color);

  const change = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));
  const round = (t: number) => Math.round(t * 100) / 100;
  /** Bornes arrondies au centième ; fin au bout du fichier = 0 (« jusqu'à la fin »). */
  const setRange = (start: number, end: number) => {
    const s = Math.max(0, round(start));
    const e = Math.min(duration, round(end));
    if (e - s < 0.05) return null;
    change({ start: s, end: e >= duration - 0.005 ? 0 : e });
    return { s, e };
  };

  const listen = (from: number, until: number) =>
    void apcdeck().call("preview", { ...slot, from, to: until >= duration - 0.005 ? 0 : until, volume: draft.volume });
  const listenSelection = () => listen(draft.start, to);
  const togglePreview = () => {
    if (preview.playing) void apcdeck().call("previewPause");
    else listenSelection();
  };

  const close = () => {
    if (dirty && !confirm("Abandonner les modifications non enregistrées ?")) return;
    onClose();
  };

  const save = async () => {
    setSaving(true);
    await apcdeck().call("previewStop");
    await call("save", { ...slot, ...draft });
    setSaving(false);
    // Coupé : le son change d'id, l'éditeur est recréé sur le nouveau fichier.
  };

  // Espace = écouter / pause (hors champs de saisie).
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      const typing = e.target instanceof HTMLInputElement && e.target.type !== "range";
      if (e.key === " " && !typing) {
        e.preventDefault();
        togglePreview();
      }
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  });

  const field = "w-full rounded-lg border border-[#938f99] bg-surface px-2.5 py-2 text-text focus:border-transparent focus:outline-2 focus:outline-accent";

  return (
    <Modal onClose={close}>
      {children}
      <Header
        title={<>Modifier « {draft.name || "Sans nom"} »</>}
        slot={slot}
        onClose={close}
        swatch={color}
      />

      <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] items-end gap-4">
        <label className="flex flex-col gap-1 text-[13px]">
          Nom
          <input className={field} value={draft.name} maxLength={40} onChange={(e) => change({ name: e.target.value })} />
        </label>
        <Slider
          label="Volume"
          display={`${draft.volume} %`}
          value={draft.volume}
          min={0}
          max={MAX_VOLUME}
          step={1}
          onChange={(v) => {
            change({ volume: v });
            if (preview.playing) void apcdeck().call("previewVolume", { volume: v });
          }}
        />
        <div className="flex flex-col gap-1 text-[13px]">
          <span className="max-w-64 truncate text-xs text-muted" title={sound.fileName}>
            {sound.fileName || "Fichier"} · {formatTime(duration, false)}
          </span>
          <Button
            disabled={uploadLabel !== null}
            onClick={() => {
              if (dirty && !confirm("Remplacer le fichier abandonne les modifications non enregistrées. Continuer ?")) return;
              pickFile();
            }}
          >
            {uploadLabel ?? "Remplacer le fichier…"}
          </Button>
        </div>
      </div>

      <div className="flex flex-col gap-1.5 text-[13px]">
        <span>Couleur <span className="text-xs text-muted">· index {draft.color}</span></span>
        <div className="grid max-w-[720px] grid-cols-[repeat(32,minmax(0,1fr))] gap-[3px]">
          {APC_PALETTE.slice(1).map((hex, i) => (
            <button
              key={i + 1}
              type="button"
              title={`Couleur ${i + 1}`}
              onClick={() => change({ color: i + 1 })}
              className={cx("aspect-square cursor-pointer rounded-[3px] border border-line", draft.color === i + 1 && "outline-2 outline-offset-1 outline-white")}
              style={{ background: hex }}
            />
          ))}
        </div>
      </div>

      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Button variant={preview.playing && !preview.paused ? "stop" : "primary"} disabled={!wave} onClick={togglePreview}>
            {!preview.playing ? "▶ Écouter la sélection" : preview.paused ? "▶ Reprendre" : "⏸ Pause"}
          </Button>
          <Button disabled={!preview.playing} onClick={() => apcdeck().call("previewStop")}>■ Arrêter</Button>
          <Button disabled={!wave} onClick={() => listen(Math.max(draft.start, to - END_PREVIEW), to)} title="Écouter les 2 dernières secondes">
            Écouter la fin
          </Button>
          <span className="text-xs text-muted">Espace : écouter / pause · Échap : fermer</span>
        </div>
        {wave ? (
          <Waveform
            peaks={wave.peaks}
            duration={duration}
            start={draft.start}
            end={to}
            color={color}
            playhead={playhead}
            onRange={(s, e, done) => {
              const range = setRange(s, e);
              if (!done || !range) return;
              // Poignée relâchée : on écoute le début de la sélection, ou la fin si c'est elle qui a bougé.
              if (done.handle === "start") listen(range.s, range.e);
              else listen(Math.max(range.s, range.e - END_PREVIEW), range.e);
            }}
            onSeek={(t) => listen(t, t < to ? to : duration)}
          />
        ) : (
          <div className="flex h-64 items-center justify-center rounded-lg bg-[#0d0c10] text-muted">
            {sound.hasFile ? "Forme d'onde…" : "Aucun fichier audio"}
          </div>
        )}
        <div className="flex flex-wrap items-end gap-3 text-[13px]">
          <TimeField label="Début (s)" value={draft.start} max={duration} onCommit={(v) => { const r = setRange(v, to); if (r) listen(r.s, r.e); }} />
          <TimeField label="Fin (s)" value={round(to)} max={duration} onCommit={(v) => { const r = setRange(draft.start, v); if (r) listen(Math.max(r.s, r.e - END_PREVIEW), r.e); }} />
          <Button size="sm" disabled={draft.start === 0 && draft.end === 0} onClick={() => change({ start: 0, end: 0 })}>
            Tout garder
          </Button>
          <span className="text-xs text-muted">
            Sélection : {formatTime(to - draft.start)} sur {formatTime(duration)}
            {removed > 0.005 && <span className="text-warn"> · {formatTime(removed)} seront supprimées à l'enregistrement</span>}
          </span>
        </div>
      </div>

      <div className="flex items-center gap-2 border-t border-line pt-4">
        <Button
          variant="danger"
          onClick={async () => {
            if (!confirm(`Supprimer le son « ${sound.name} » et son fichier ?`)) return;
            await call("delete", slot);
            onClose();
          }}
        >
          Supprimer le son
        </Button>
        <span className="ml-auto text-xs text-muted">{saving ? "Enregistrement…" : dirty ? "Modifications non enregistrées" : "Aucune modification"}</span>
        <Button onClick={close}>{dirty ? "Annuler" : "Fermer"}</Button>
        <Button variant="primary" disabled={!dirty || saving} onClick={save}>
          Enregistrer
        </Button>
      </div>
    </Modal>
  );
}

// --- éléments ------------------------------------------------------------------------------------

/** Fenêtre modale : Échap ou clic à côté = [onClose]. */
function Modal({ onClose, narrow, children }: { onClose: () => void; narrow?: boolean; children: ReactNode }) {
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const key = (e: KeyboardEvent) => e.key === "Escape" && closeRef.current();
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, []);

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70 p-4" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        role="dialog"
        aria-modal="true"
        className={cx(
          "flex max-h-[94vh] flex-col gap-4 overflow-auto rounded-2xl border border-line bg-surface p-5 shadow-[0_20px_60px_#000a]",
          narrow ? "w-[min(480px,96vw)]" : "w-[min(1500px,96vw)]",
        )}
      >
        {children}
      </div>
    </div>
  );
}

function Header({ title, slot, onClose, swatch }: { title: ReactNode; slot: Slot; onClose: () => void; swatch?: string }) {
  return (
    <div className="flex items-center gap-3">
      {swatch && <span className="h-5 w-5 rounded" style={{ background: swatch }} />}
      <h2 className="m-0 text-lg font-semibold">{title}</h2>
      <span className="text-xs text-muted">pad {slot.x + 1}·{slot.y + 1} · page {slot.row + 1}·{slot.col + 1}</span>
      <button type="button" className="ml-auto cursor-pointer rounded-full px-2 text-lg text-muted hover:text-text" onClick={onClose} title="Fermer">
        ✕
      </button>
    </div>
  );
}

/** Champ numérique validé à la sortie du champ ou avec Entrée (pas à chaque frappe). */
function TimeField({ label, value, max, onCommit }: { label: string; value: number; max: number; onCommit: (v: number) => void }) {
  const [text, setText] = useState(String(value));
  useEffect(() => setText(String(value)), [value]);
  const commit = () => {
    const v = Number(text.replace(",", "."));
    if (Number.isFinite(v) && v !== value) onCommit(v);
    else setText(String(value));
  };
  return (
    <label className="flex flex-col gap-1">
      {label}
      <input
        className="w-28 rounded-lg border border-[#938f99] bg-surface px-2 py-1.5 text-text tabular-nums focus:border-transparent focus:outline-2 focus:outline-accent"
        inputMode="decimal"
        value={text}
        max={max}
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === "Enter" && commit()}
      />
    </label>
  );
}

// --- écoute ----------------------------------------------------------------------------------

/** État de l'écoute de l'éditeur, s'il concerne ce son ([run] change à chaque nouvelle écoute). */
function usePreview(id: string): Preview {
  const [preview, setPreview] = useState<Preview>({ playing: false, paused: false, run: 0 });
  usePluginEvent<Preview>("preview", (p) => setPreview(p.id === id && p.playing ? p : { playing: false, paused: false, run: 0 }));
  return preview;
}

/** Position estimée de l'écoute (secondes dans le fichier), pauses exclues ; null à l'arrêt. */
function usePlayhead(preview: Preview): number | null {
  const [position, setPosition] = useState<number | null>(null);
  const played = useRef(0);

  useEffect(() => {
    played.current = 0;
    setPosition(preview.playing ? (preview.from ?? 0) : null);
  }, [preview.run, preview.playing, preview.from]);

  useEffect(() => {
    if (!preview.playing || preview.paused) return;
    const t0 = performance.now();
    const base = played.current;
    const from = preview.from ?? 0;
    let frame = requestAnimationFrame(function tick() {
      played.current = base + (performance.now() - t0) / 1000;
      setPosition(from + played.current);
      frame = requestAnimationFrame(tick);
    });
    return () => cancelAnimationFrame(frame);
  }, [preview.run, preview.playing, preview.paused, preview.from]);

  return position;
}
