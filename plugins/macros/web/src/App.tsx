import { createContext, useCallback, useContext, useEffect, useRef, useState, type MouseEvent as ReactMouseEvent, type ReactNode } from "react";
import { Button, Segmented, cx, padHex, textOn, usePluginEvent, usePluginState, APC_PALETTE } from "@apcdeck/web";
import type { Macro, MacrosState, Slot } from "./types";

const COLS = 8;
const ROWS = 5;
const PLACEHOLDERS: Record<string, string> = {
  powershell: '# Exemples :\n# Start-Process "https://claude.ai"\n# Start-Process notepad\n# Get-ChildItem $HOME',
  cmd: '@echo off\nrem Exemples :\nrem start "" "https://claude.ai"\nrem start notepad',
  bash: '# Exemples :\n# echo "Bonjour"\n# ls -la ~',
};
const EFFECT_LABELS: Record<Macro["effect"], string> = { fixe: "Fixe", pulse: "Pulse", clignote: "Clignotant" };
const EFFECT_ANIM: Record<Macro["effect"], string> = { fixe: "", pulse: "animate-pulse-led", clignote: "animate-blink-led" };

type Call = (action: string, data?: unknown) => Promise<MacrosState | null>;
type Draft = Omit<Macro, "running">;
const sameSlot = (a: Slot, b: Slot) => a.row === b.row && a.col === b.col && a.x === b.x && a.y === b.y;

export function App() {
  const { state, call, error } = usePluginState<MacrosState>();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const editor = useAutosave(call);

  const select = useCallback(
    async (id: string | null) => {
      await editor.flush();
      setSelectedId(id);
    },
    [editor],
  );

  if (!state) return <div className="p-6 text-muted">{error ?? "Chargement…"}</div>;
  const selected = state.macros.find((m) => m.id === selectedId) ?? null;

  const create = async () => {
    await editor.flush();
    const next = await call("save", { name: "Nouvelle macro", description: "", shell: state.shells[0]?.id, terminal: false, color: 45, effect: "fixe", script: "" });
    if (next?.savedId) setSelectedId(next.savedId);
  };

  return (
    <DragProvider state={state} call={call}>
      <div className="grid h-screen grid-cols-[230px_minmax(420px,1fr)_360px]">
        <aside className="overflow-auto border-r border-line p-4">
          <div className="flex items-center justify-between">
            <h2 className="m-0 text-base font-semibold">Macros</h2>
            <Button size="sm" variant="primary" onClick={create}>+ Nouvelle</Button>
          </div>
          <p className="text-xs text-muted">Glisse une macro sur un pad pour la placer.</p>
          <MacroList state={state} selectedId={selectedId} onSelect={select} call={call} />
        </aside>
        <section className="overflow-auto p-4">
          <Board state={state} call={call} onSelect={select} />
        </section>
        <section className="overflow-auto border-l border-line p-4">
          {selected ? (
            <Editor key={selected.id} macro={selected} shells={state.shells} effects={state.effects} call={call} autosave={editor} />
          ) : (
            <p className="text-muted">Sélectionne une macro, ou crée-en une.</p>
          )}
        </section>
      </div>
    </DragProvider>
  );
}

// --- enregistrement automatique -------------------------------------------------------------

interface Autosave {
  schedule: (draft: Draft, delay: number) => void;
  flush: () => Promise<void>;
  status: string;
}

/** Envoie le brouillon après une pause de frappe (ou tout de suite) ; les envois partent dans l'ordre. */
function useAutosave(call: Call): Autosave {
  const [status, setStatus] = useState("");
  const timer = useRef<number | undefined>(undefined);
  const pending = useRef<Draft | null>(null);
  const queue = useRef<Promise<unknown>>(Promise.resolve());

  const send = useCallback(() => {
    window.clearTimeout(timer.current);
    const draft = pending.current;
    pending.current = null;
    if (!draft) return queue.current;
    queue.current = queue.current
      .then(() => call("save", draft))
      .then(() => setStatus(pending.current ? "Modifications en cours…" : "Enregistré"));
    return queue.current;
  }, [call]);

  const schedule = useCallback(
    (draft: Draft, delay: number) => {
      pending.current = draft;
      setStatus("Modifications en cours…");
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(send, delay);
    },
    [send],
  );

  const flush = useCallback(async () => {
    await send();
  }, [send]);

  useEffect(() => {
    const beforeUnload = () => void send();
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [send]);

  return { schedule, flush, status };
}

// --- liste ----------------------------------------------------------------------------------

function MacroList({ state, selectedId, onSelect, call }: { state: MacrosState; selectedId: string | null; onSelect: (id: string) => void; call: Call }) {
  const drag = useDrag();
  return (
    <div className="mt-2 flex flex-col gap-1.5">
      {state.macros.length === 0 && <p className="text-xs text-muted">Aucune macro pour l'instant.</p>}
      {state.macros.map((m) => (
        <div
          key={m.id}
          onMouseDown={(e) => drag.start(e, m.id, null, () => onSelect(m.id))}
          className={cx(
            "flex cursor-grab items-center gap-2.5 rounded-xl border bg-surface-2 px-2.5 py-2 select-none",
            m.id === selectedId ? "border-accent" : "border-transparent",
          )}
        >
          <span
            className={cx("h-3.5 w-3.5 shrink-0 rounded", EFFECT_ANIM[m.effect], m.running && "outline-2 outline-offset-2 outline-ok")}
            style={{ background: padHex(m.color) }}
          />
          <span className="min-w-0 flex-1">
            <span className="block truncate font-semibold">{m.name}</span>
            <span className="block truncate text-xs text-muted">{m.description || "—"}</span>
          </span>
          <Button
            size="sm"
            variant={m.running ? "stop" : "outline"}
            onMouseDown={(e) => e.stopPropagation()}
            onClick={() => call("run", { id: m.id })}
            title={m.running ? "Arrêter" : "Lancer"}
          >
            {m.running ? "■" : "▶"}
          </Button>
        </div>
      ))}
    </div>
  );
}

// --- pages et pads --------------------------------------------------------------------------

function Board({ state, call, onSelect }: { state: MacrosState; call: Call; onSelect: (id: string) => void }) {
  const drag = useDrag();
  const { current } = state;
  const counts = new Map<string, number>();
  state.slots.forEach((s) => counts.set(`${s.row}.${s.col}`, (counts.get(`${s.row}.${s.col}`) ?? 0) + 1));
  const at = (x: number, y: number) => state.slots.find((s) => sameSlot(s, { ...current, x, y }));

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-baseline gap-3">
        <h2 className="m-0 text-base font-semibold">Page {current.row + 1}·{current.col + 1}</h2>
        <span className="text-xs text-muted">colonne verte × ligne rouge</span>
      </div>
      <div className="grid w-fit grid-cols-8 gap-[3px]">
        {Array.from({ length: 40 }, (_, i) => {
          const row = Math.floor(i / 8);
          const col = i % 8;
          const isCurrent = row === current.row && col === current.col;
          const count = counts.get(`${row}.${col}`) ?? 0;
          return (
            <button
              key={i}
              type="button"
              title={`Page ${row + 1}·${col + 1}`}
              onClick={() => call("page", { row, col })}
              className={cx(
                "h-5 w-7.5 cursor-pointer rounded-[3px] text-[10px]",
                isCurrent ? "bg-accent font-semibold text-on-accent" : count ? "bg-[#4a4458]" : "bg-white/5",
              )}
            >
              {count || ""}
            </button>
          );
        })}
      </div>
      <div className="grid grid-cols-8 gap-2" style={{ maxWidth: COLS * 84 }}>
        {Array.from({ length: COLS * ROWS }, (_, i) => {
          const x = i % COLS;
          const y = Math.floor(i / COLS);
          const slot: Slot = { ...current, x, y };
          const placed = at(x, y);
          const macro = placed && state.macros.find((m) => m.id === placed.id);
          const color = macro ? (macro.running ? "#22e04a" : padHex(macro.color)) : undefined;
          return (
            <div
              key={i}
              data-slot={JSON.stringify(slot)}
              onMouseDown={macro ? (e) => drag.start(e, macro.id, slot, () => onSelect(macro.id)) : undefined}
              className={cx(
                "relative flex aspect-square items-center justify-center overflow-hidden rounded-[10px] border border-line p-1.5 text-center text-[11px] font-semibold select-none",
                macro ? "cursor-grab" : "bg-pad-off",
                macro && !macro.running && EFFECT_ANIM[macro.effect],
                drag.target && sameSlot(drag.target, slot) && "outline-3 outline-accent",
              )}
              style={color ? { background: color, color: textOn(color) } : undefined}
              title={macro?.description || macro?.name}
            >
              {macro?.name}
              {macro && (
                <span
                  className="absolute top-0.5 right-1.5 cursor-pointer text-[11px] opacity-70"
                  onMouseDown={(e) => e.stopPropagation()}
                  onClick={() => call("clear", slot)}
                >
                  ✕
                </span>
              )}
              {macro?.running && <span className="absolute inset-x-0 bottom-1 text-[9px]">▶ en cours</span>}
            </div>
          );
        })}
      </div>
      <p className="text-xs text-muted">
        Glisse un pad pour le déplacer (échange si occupé) · lâche-le hors de la grille pour le retirer · clic = modifier la macro.
      </p>
    </div>
  );
}

// --- éditeur ----------------------------------------------------------------------------------

function Editor({ macro, shells, effects, call, autosave }: { macro: Macro; shells: MacrosState["shells"]; effects: Macro["effect"][]; call: Call; autosave: Autosave }) {
  const [draft, setDraft] = useState<Draft>(() => ({ ...macro }));
  const [output, setOutput] = useState<string[]>([]);
  const outputRef = useRef<HTMLPreElement>(null);

  useEffect(() => {
    void window.apcdeck?.call<{ id: string; lines: string[] }>("output", { id: macro.id }).then((o) => o && setOutput(o.lines));
  }, [macro.id]);
  usePluginEvent<{ id: string; line: string }>("output", ({ id, line }) => {
    if (id === macro.id) setOutput((lines) => [...lines, line].slice(-300));
  });
  useEffect(() => {
    outputRef.current?.scrollTo(0, outputRef.current.scrollHeight);
  }, [output]);

  const change = (patch: Partial<Draft>, delay: number) => {
    const next = { ...draft, ...patch };
    setDraft(next);
    autosave.schedule(next, delay);
  };

  const field = "w-full rounded-lg border border-[#938f99] bg-surface px-2.5 py-2 text-text focus:border-transparent focus:outline-2 focus:outline-accent";

  return (
    <form className="flex flex-col gap-3" onSubmit={(e) => e.preventDefault()}>
      <label className="flex flex-col gap-1 text-[13px]">
        Nom
        <input className={field} value={draft.name} maxLength={60} onChange={(e) => change({ name: e.target.value }, 500)} />
      </label>
      <label className="flex flex-col gap-1 text-[13px]">
        Description
        <input className={field} value={draft.description} maxLength={200} onChange={(e) => change({ description: e.target.value }, 500)} />
      </label>
      <div className="flex items-end gap-3">
        <label className="flex flex-1 flex-col gap-1 text-[13px]">
          Shell
          <select className={field} value={draft.shell} onChange={(e) => change({ shell: e.target.value }, 0)}>
            {shells.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
          </select>
        </label>
        <label className="mb-2 flex items-center gap-1.5 text-[13px]">
          <input type="checkbox" checked={draft.terminal} onChange={(e) => change({ terminal: e.target.checked }, 0)} />
          Fenêtre de terminal
        </label>
      </div>
      <label className="flex flex-col gap-1 text-[13px]">
        Script
        <textarea
          className={cx(field, "resize-y font-mono text-[12.5px]")}
          rows={9}
          spellCheck={false}
          value={draft.script}
          placeholder={PLACEHOLDERS[draft.shell] ?? ""}
          onChange={(e) => change({ script: e.target.value }, 500)}
          onKeyDown={(e) => {
            if (e.key !== "Tab") return;
            e.preventDefault();
            const t = e.currentTarget;
            const at = t.selectionStart;
            const value = t.value.slice(0, at) + "    " + t.value.slice(t.selectionEnd);
            change({ script: value }, 500);
            requestAnimationFrame(() => t.setSelectionRange(at + 4, at + 4));
          }}
        />
      </label>
      <div className="flex flex-col gap-1.5 text-[13px]">
        <span>Couleur <span className="text-xs text-muted">· index {draft.color}</span></span>
        <div className="grid grid-cols-16 gap-[3px]">
          {APC_PALETTE.slice(1).map((hex, i) => (
            <button
              key={i + 1}
              type="button"
              title={`Couleur ${i + 1}`}
              onClick={() => change({ color: i + 1 }, 0)}
              className={cx("aspect-square cursor-pointer rounded-[3px] border border-line", draft.color === i + 1 && "outline-2 outline-offset-1 outline-white")}
              style={{ background: hex }}
            />
          ))}
        </div>
      </div>
      <div className="flex flex-col gap-1.5 text-[13px]">
        <span>Affichage</span>
        <Segmented
          options={effects.map((e) => ({
            id: e,
            label: (
              <>
                <span className={cx("inline-block h-3 w-3 rounded", EFFECT_ANIM[e])} style={{ background: padHex(draft.color) }} />
                {EFFECT_LABELS[e]}
              </>
            ),
          }))}
          value={draft.effect}
          onChange={(e) => change({ effect: e }, 0)}
        />
      </div>
      <div className="flex items-center gap-2">
        <Button
          variant={macro.running ? "stop" : "outline"}
          onClick={async () => {
            await autosave.flush();
            void call("run", { id: macro.id });
          }}
        >
          {macro.running ? "Arrêter" : "Lancer"}
        </Button>
        <Button
          variant="danger"
          onClick={async () => {
            if (!confirm(`Supprimer la macro « ${macro.name} » ?`)) return;
            await autosave.flush();
            void call("delete", { id: macro.id });
          }}
        >
          Supprimer
        </Button>
        <span className="ml-auto text-xs text-muted">{autosave.status}</span>
      </div>
      <div className="flex flex-col gap-1.5 text-[13px]">
        <span>Sortie</span>
        <pre
          ref={outputRef}
          className="m-0 max-h-56 min-h-24 overflow-auto rounded-lg bg-[#0d0c10] px-2.5 py-2 font-mono text-xs whitespace-pre-wrap select-text"
        >
          {output.join("\n")}
        </pre>
      </div>
    </form>
  );
}

// --- glisser-déposer (souris) -----------------------------------------------------------------

interface DragApi {
  start: (e: ReactMouseEvent, id: string, from: Slot | null, onClick: () => void) => void;
  target: Slot | null;
}

const DragContext = createContext<DragApi>({ start: () => {}, target: null });

function useDrag(): DragApi {
  return useContext(DragContext);
}

/** Glisser une macro (de la liste ou d'un pad) vers un pad ; lâchée hors de la grille depuis un pad = retirée. */
function DragProvider({ state, call, children }: { state: MacrosState; call: Call; children: ReactNode }) {
  const [drag, setDrag] = useState<{ id: string; from: Slot | null; x: number; y: number; moved: boolean; onClick: () => void } | null>(null);
  const [pointer, setPointer] = useState({ x: 0, y: 0 });
  const [target, setTarget] = useState<Slot | null>(null);

  const slotAt = (x: number, y: number): Slot | null => {
    const el = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-slot]");
    return el ? (JSON.parse(el.dataset.slot ?? "null") as Slot) : null;
  };

  useEffect(() => {
    if (!drag) return;
    const move = (e: MouseEvent) => {
      if (!drag.moved && Math.hypot(e.clientX - drag.x, e.clientY - drag.y) > 4) setDrag({ ...drag, moved: true });
      setPointer({ x: e.clientX, y: e.clientY });
      setTarget(slotAt(e.clientX, e.clientY));
    };
    const up = (e: MouseEvent) => {
      setDrag(null);
      setTarget(null);
      if (!drag.moved) return drag.onClick();
      const to = slotAt(e.clientX, e.clientY);
      if (to && drag.from) void call("move", { from: drag.from, to });
      else if (to) void call("place", { ...to, id: drag.id });
      else if (drag.from) void call("clear", drag.from);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
    return () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    };
  }, [drag, call]);

  const api: DragApi = {
    start: (e, id, from, onClick) => {
      if (e.button !== 0) return;
      e.preventDefault();
      setDrag({ id, from, x: e.clientX, y: e.clientY, moved: false, onClick });
      setPointer({ x: e.clientX, y: e.clientY });
    },
    target,
  };

  const dragged = drag?.moved ? state.macros.find((m) => m.id === drag.id) : null;
  return (
    <DragContext.Provider value={api}>
      {children}
      {dragged && (
        <div
          className="pointer-events-none fixed z-30 rounded-lg px-3 py-1.5 text-xs font-semibold shadow-[0_6px_20px_#0008]"
          style={{ left: pointer.x + 12, top: pointer.y + 12, background: padHex(dragged.color), color: textOn(padHex(dragged.color)) }}
        >
          {dragged.name}
        </div>
      )}
    </DragContext.Provider>
  );
}
