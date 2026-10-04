import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, cx } from "@apcdeck/web";
import { cmd, uploadJars, useAppState, useToasts } from "./api";
import { ApcView } from "./ApcView";
import { ConfigPanel } from "./ConfigPanel";
import { PagerEditor } from "./PagerEditor";
import { PluginPage } from "./PluginPage";
import { RemoteView } from "./RemoteView";
import { DEVICE, REMOTE, Sidebar } from "./Sidebar";
import type { AppState, PluginUpdate, Update } from "./types";

type MobileTab = "list" | "main" | "config";

/** État du contrôle à distance, en quelques mots (sous son entrée de la liste). */
function remoteHint(remote: AppState["remote"]): string {
  if (!remote) return "";
  const c = remote.client;
  if (c.state === "CONNECTED") return `Pilote ${c.serverName}`;
  if (c.state === "CONNECTING") return `Connexion à ${c.serverName ?? "un PC"}…`;
  const connected = remote.server.devices.filter((d) => d.connected).length;
  if (remote.server.running) return connected > 0 ? `${connected} mobile(s) connecté(s)` : "En attente d'un mobile";
  return "Piloter ce PC depuis un mobile, ou un PC d'ici";
}

export function App() {
  const state = useAppState();
  const [selected, setSelected] = useState<string>(() => sessionStorage.getItem("selected") ?? DEVICE);
  // Petit écran (mobile) : une seule colonne à la fois.
  const [tab, setTab] = useState<MobileTab>("main");
  const select = (id: string) => {
    setSelected(id);
    sessionStorage.setItem("selected", id);
    setTab("main");
  };

  const plugin = state.plugins.find((p) => p.id === selected) ?? null;
  // Plugin retiré : retour à la vue de l'appareil.
  useEffect(() => {
    if (selected !== DEVICE && selected !== REMOTE && state.plugins.length > 0 && !plugin) select(DEVICE);
  }, [selected, state.plugins, plugin]);

  // Ce mobile pilote un PC : la vue de l'appareil devient l'APC de ce PC.
  const remoteServer = state.remote?.client.state === "CONNECTED" ? state.remote.client.serverName : null;
  // Plugin avec page web mais sans options : sa page prend aussi la place du panneau de configuration.
  const showConfig = selected !== REMOTE && !(plugin?.webUrl && plugin.fields.length === 0);
  const column = (t: MobileTab) => (tab === t ? "" : "hidden lg:block");

  return (
    <JarDropZone>
      <div className="flex h-full flex-col">
        <TopBar state={state} onVirtual={() => select(DEVICE)} />
        {state.update && <UpdateBanner update={state.update} />}
        {state.update && state.update.plugins.length > 0 && <PluginUpdatesBanner plugins={state.update.plugins} />}
        <nav className="flex flex-none border-b border-line lg:hidden">
          {([["list", "Plugins"], ["main", "Vue"], ...(showConfig ? [["config", "Options"]] : [])] as [MobileTab, string][]).map(([id, label]) => (
            <button
              key={id}
              type="button"
              onClick={() => setTab(id)}
              className={cx("flex-1 cursor-pointer border-b-2 py-2 text-[13px]", tab === id ? "border-accent text-text" : "border-transparent text-muted")}
            >
              {label}
            </button>
          ))}
        </nav>
        <div className={cx("min-h-0 flex-1 lg:grid", showConfig ? "lg:grid-cols-[300px_minmax(0,1fr)_360px]" : "lg:grid-cols-[300px_minmax(0,1fr)]")}>
          <Sidebar plugins={state.plugins} selected={selected} onSelect={select} remoteHint={remoteHint(state.remote)} className={cx("h-full", column("list"))} />
          <main className={cx("relative h-full min-h-0 min-w-0 overflow-auto", column("main"))}>
            {selected === REMOTE ? (
              <RemoteView remote={state.remote} />
            ) : !plugin ? (
              <ApcView state={state} virtual={!!state.device.virtual || remoteServer !== null} remoteServer={remoteServer} />
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
          {showConfig && <ConfigPanel plugin={plugin} learning={state.learning} className={cx("h-full", column("config"))} />}
        </div>
      </div>
      <Toasts />
    </JarDropZone>
  );
}

function TopBar({ state, onVirtual }: { state: ReturnType<typeof useAppState>; onVirtual: () => void }) {
  const fileInput = useRef<HTMLInputElement>(null);
  const { connected, detail, virtual } = state.device;
  const virtualOn = !!state.settings?.virtual;
  return (
    <header className="flex flex-none flex-wrap items-center gap-x-3 gap-y-2 border-b border-line px-4 py-2.5">
      <h1 className="m-0 text-xl font-bold">APC Deck</h1>
      <span className={cx("h-2.5 w-2.5 rounded-full", connected ? "bg-ok" : virtual ? "bg-accent" : "bg-ko")} />
      <span className="min-w-0 flex-1 truncate text-xs" title={detail}>
        {connected ? "APC connecté" : virtual ? "APC virtuel (aucun APC branché)" : detail}
      </span>
      {/* Proposé quand aucun APC n'est branché ; reste visible tant qu'il est activé, pour pouvoir le couper. */}
      {state.settings && (!connected || virtualOn) && state.remote?.client.state !== "CONNECTED" && (
        <Button
          variant={virtualOn ? "primary" : "outline"}
          title={virtualOn
            ? "Couper l'APC virtuel"
            : "Utiliser l'interface comme APC (souris, tactile, clavier de l'ordinateur) tant qu'aucun APC n'est branché"}
          onClick={() => {
            void cmd("virtual", { enabled: !virtualOn });
            if (!virtualOn) onVirtual();
          }}
        >
          {virtualOn ? "APC virtuel : activé" : "APC virtuel"}
        </Button>
      )}
      {state.settings && (
        <label className="hidden items-center gap-1.5 text-[13px] lg:flex">
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
      {state.update && <VersionButton update={state.update} />}
      <Button onClick={() => cmd("reconnect")}>Reconnecter</Button>
      {state.settings?.platform !== "android" && <Button className="hidden lg:inline-flex" onClick={() => cmd("openFolder")}>Dossier</Button>}
      <Button variant="primary" className="hidden lg:inline-flex" onClick={() => fileInput.current?.click()}>Ajouter un plugin…</Button>
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

/** Version actuelle ; un clic lance une recherche de mise à jour. */
function VersionButton({ update }: { update: Update }) {
  if (!update.current) return <span className="text-xs text-muted" title="Lancé depuis les sources : pas de mises à jour">dev</span>;
  const checked = update.checkedAt ? new Date(update.checkedAt).toLocaleString() : "jamais";
  const label =
    update.status === "CHECKING" ? "Recherche…"
      : update.status === "UP_TO_DATE" ? `v${update.current} · à jour`
        : `v${update.current}`;
  return (
    <button
      type="button"
      disabled={update.status === "CHECKING" || update.status === "DOWNLOADING" || update.status === "INSTALLING"}
      onClick={() => cmd("checkUpdate")}
      title={`Rechercher des mises à jour (dernière recherche : ${checked})${update.error ? `
${update.error}` : ""}`}
      className={cx("cursor-pointer text-xs text-muted hover:text-text disabled:cursor-default", update.status === "ERROR" && "text-danger")}
    >
      {label}
    </button>
  );
}

/** Bandeau sous la barre du haut quand une version plus récente est publiée sur GitHub. */
function UpdateBanner({ update }: { update: Update }) {
  const busy = update.status === "DOWNLOADING" || update.status === "INSTALLING";
  if (update.status !== "AVAILABLE" && !busy) return null;
  const install = () => {
    if (window.confirm(`Installer APC Deck ${update.latest} ?

L'installeur va se lancer et l'application se fermera pour le laisser faire. Une fois l'installation terminée, une fenêtre proposera de la relancer (cette page se rechargera toute seule).`)) {
      void cmd("installUpdate");
    }
  };
  return (
    <div className="flex flex-none items-center gap-3 border-b border-line bg-accent/12 px-4 py-2 text-[13px]">
      <span className="flex-1">
        <b>APC Deck {update.latest}</b> est disponible (tu as {update.current}).
        {update.error && <span className="ml-2 text-danger">{update.error}</span>}
      </span>
      {update.pageUrl && (
        <a href={update.pageUrl} target="_blank" rel="noreferrer" className="text-muted underline hover:text-text">
          Nouveautés
        </a>
      )}
      {update.status === "DOWNLOADING" ? (
        <span className="tabular-nums">Téléchargement {Math.round((update.progress ?? 0) * 100)} %</span>
      ) : update.status === "INSTALLING" ? (
        <span>Lancement de l'installeur…</span>
      ) : update.assetName ? (
        <Button variant="primary" size="sm" onClick={install}>Installer</Button>
      ) : (
        update.pageUrl && <a href={update.pageUrl} target="_blank" rel="noreferrer"><Button variant="primary" size="sm">Télécharger</Button></a>
      )}
    </div>
  );
}

/** Bandeau des plugins dont le dépôt publie une version plus récente (installées à chaud, sans redémarrer). */
function PluginUpdatesBanner({ plugins }: { plugins: PluginUpdate[] }) {
  const pending = plugins.filter((p) => !p.installing);
  return (
    <div className="flex flex-none flex-wrap items-center gap-x-4 gap-y-1.5 border-b border-line bg-accent/6 px-4 py-2 text-[13px]">
      <b>Mises à jour de plugins</b>
      {plugins.map((p) => (
        <span key={p.id} className="flex items-center gap-2">
          {p.pageUrl ? (
            <a href={p.pageUrl} target="_blank" rel="noreferrer" className="underline hover:text-text" title="Nouveautés">
              {p.name}
            </a>
          ) : (
            p.name
          )}
          <span className="text-muted tabular-nums">{p.current} → {p.latest}</span>
          {p.installing ? (
            <span className="text-muted">installation…</span>
          ) : (
            <Button size="sm" onClick={() => cmd("installPluginUpdate", { id: p.id })}>Mettre à jour</Button>
          )}
          {p.error && <span className="text-danger" title={p.error}>échec</span>}
        </span>
      ))}
      <span className="flex-1" />
      {pending.length > 1 && (
        <Button variant="primary" size="sm" onClick={() => cmd("installPluginUpdates")}>Tout mettre à jour</Button>
      )}
    </div>
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
