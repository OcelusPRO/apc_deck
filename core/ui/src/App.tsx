import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, cx } from "@apcdeck/web";
import { cmd, uploadJars, useAppState, useToasts } from "./api";
import { ApcView } from "./ApcView";
import { ConfigPanel } from "./ConfigPanel";
import { PagerEditor } from "./PagerEditor";
import { PluginPage } from "./PluginPage";
import { DEVICE, Sidebar } from "./Sidebar";

export function App() {
  const state = useAppState();
  const [selected, setSelected] = useState<string>(() => sessionStorage.getItem("selected") ?? DEVICE);
  const select = (id: string) => {
    setSelected(id);
    sessionStorage.setItem("selected", id);
  };

  const plugin = state.plugins.find((p) => p.id === selected) ?? null;
  // Plugin retiré : retour à la vue de l'appareil.
  useEffect(() => {
    if (selected !== DEVICE && state.plugins.length > 0 && !plugin) select(DEVICE);
  }, [selected, state.plugins, plugin]);

  // Plugin avec page web mais sans options : sa page prend aussi la place du panneau de configuration.
  const showConfig = !(plugin?.webUrl && plugin.fields.length === 0);

  return (
    <JarDropZone>
      <div className="flex h-full flex-col">
        <TopBar state={state} />
        <div className={cx("grid min-h-0 flex-1", showConfig ? "grid-cols-[300px_minmax(0,1fr)_360px]" : "grid-cols-[300px_minmax(0,1fr)]")}>
          <Sidebar plugins={state.plugins} selected={selected} onSelect={select} />
          <main className="relative min-h-0 min-w-0 overflow-auto">
            {!plugin ? (
              <ApcView state={state} />
            ) : plugin.manager && state.pager ? (
              <PagerEditor pager={state.pager} plugins={state.plugins} />
            ) : plugin.webUrl ? (
              <PluginPage key={plugin.webUrl} plugin={plugin} />
            ) : (
              <p className="p-8 text-muted">
                {plugin.status === "ENABLED"
                  ? `${plugin.name} n'a pas d'interface (pas de dossier web/ dans son jar).`
                  : plugin.status === "DISABLED"
                    ? `${plugin.name} est désactivé.`
                    : `${plugin.name} est en erreur : ${plugin.error}`}
              </p>
            )}
          </main>
          {showConfig && <ConfigPanel plugin={plugin} learning={state.learning} />}
        </div>
      </div>
      <Toasts />
    </JarDropZone>
  );
}

function TopBar({ state }: { state: ReturnType<typeof useAppState> }) {
  const fileInput = useRef<HTMLInputElement>(null);
  return (
    <header className="flex flex-none items-center gap-3 border-b border-line px-4 py-2.5">
      <h1 className="m-0 text-xl font-bold">APC Deck</h1>
      <span className={cx("h-2.5 w-2.5 rounded-full", state.device.connected ? "bg-ok" : "bg-ko")} />
      <span className="min-w-0 flex-1 truncate text-xs" title={state.device.detail}>
        {state.device.connected ? "APC connecté" : state.device.detail}
      </span>
      {state.settings && (
        <label className="flex items-center gap-1.5 text-[13px]">
          Mode
          <select
            value={state.settings.mode}
            onChange={(e) => cmd("mode", { mode: e.target.value })}
            className="rounded-lg border border-[#938f99] bg-surface px-2 py-1.5 text-text"
          >
            {state.settings.modes.map((m) => <option key={m} value={m}>{m.toLowerCase()}</option>)}
          </select>
        </label>
      )}
      <Button onClick={() => cmd("reconnect")}>Reconnecter</Button>
      <Button onClick={() => cmd("openFolder")}>Dossier</Button>
      <Button variant="primary" onClick={() => fileInput.current?.click()}>Ajouter un plugin…</Button>
      <input
        ref={fileInput}
        type="file"
        accept=".jar"
        multiple
        hidden
        onChange={(e) => {
          if (e.target.files) void uploadJars(e.target.files);
          e.target.value = "";
        }}
      />
    </header>
  );
}

/** Glisser des .jar sur la fenêtre pour les installer ou les mettre à jour. */
function JarDropZone({ children }: { children: ReactNode }) {
  const [over, setOver] = useState(false);
  const depth = useRef(0);
  useEffect(() => {
    const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes("Files");
    const enter = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      depth.current++;
      setOver(true);
    };
    const leave = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      if (--depth.current <= 0) {
        depth.current = 0;
        setOver(false);
      }
    };
    const overHandler = (e: DragEvent) => hasFiles(e) && e.preventDefault();
    const drop = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      depth.current = 0;
      setOver(false);
      if (e.dataTransfer) void uploadJars(e.dataTransfer.files);
    };
    window.addEventListener("dragenter", enter);
    window.addEventListener("dragleave", leave);
    window.addEventListener("dragover", overHandler);
    window.addEventListener("drop", drop);
    return () => {
      window.removeEventListener("dragenter", enter);
      window.removeEventListener("dragleave", leave);
      window.removeEventListener("dragover", overHandler);
      window.removeEventListener("drop", drop);
    };
  }, []);
  return (
    <>
      {children}
      {over && (
        <div className="pointer-events-none fixed inset-3 z-20 flex items-center justify-center rounded-2xl border-2 border-accent bg-accent/12 text-2xl">
          Dépose le .jar pour installer le plugin
        </div>
      )}
    </>
  );
}

function Toasts() {
  const toasts = useToasts();
  return (
    <div className="fixed right-4 bottom-4 z-40 flex flex-col gap-2">
      {toasts.map((t) => (
        <div key={t.id} className={cx("toast max-w-[420px] rounded-xl bg-surface-3 px-3.5 py-2.5 shadow-[0_6px_20px_#0008]", t.error && "error border-l-3 border-danger")}>
          {t.message}
        </div>
      ))}
    </div>
  );
}
