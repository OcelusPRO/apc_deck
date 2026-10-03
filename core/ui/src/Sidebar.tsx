import type { ReactNode } from "react";
import { Button, Switch, cx, padHex } from "@apcdeck/web";
import { cmd } from "./api";
import type { PluginView } from "./types";

/** Entrée « appareil » de la liste (miroir de l'APC). */
export const DEVICE = "#apc";

function statusText(p: PluginView): string {
  if (p.status === "ERROR") return `Erreur : ${p.error ?? "?"}`;
  if (p.manager) return `Gestionnaire${p.foreground ? " · au premier plan" : ""}`;
  if (p.status === "DISABLED") return "Désactivé";
  return (p.paused ? "En pause" : p.foreground ? "Au premier plan" : "Actif") + (p.listening ? " · écoute en arrière-plan" : "");
}

export function Sidebar({ plugins, selected, onSelect }: { plugins: PluginView[]; selected: string; onSelect: (id: string) => void }) {
  return (
    <aside className="overflow-auto border-r border-line p-3">
      <div className="flex items-center justify-between">
        <h2 className="m-0 text-base font-semibold">Plugins</h2>
        <Button variant="text" onClick={() => cmd("rescan")}>Rescanner le dossier</Button>
      </div>
      <p className="text-xs text-muted">Glisse un .jar sur la fenêtre, ou copie-le dans le dossier plugins/ : il est détecté automatiquement.</p>
      <div className="mt-2 flex flex-col gap-2">
        <Card selected={selected === DEVICE} onClick={() => onSelect(DEVICE)} dataId={DEVICE}>
          <span className="font-semibold">APC Key 25 mk2</span>
          <span className="block text-xs text-muted">Miroir des LED, potars, clavier, journal</span>
        </Card>
        {plugins.map((p) => {
          const enabled = p.status === "ENABLED";
          return (
            <Card key={p.id} selected={p.id === selected} foreground={p.foreground} onClick={() => onSelect(p.id)} dataId={p.id}>
              <div className="flex items-center gap-2">
                <span className="h-3 w-3 shrink-0 rounded-full" style={{ background: padHex(p.color) }} />
                <span className="min-w-0 flex-1 truncate font-semibold">{p.name}</span>
                <span className="text-[11px] text-muted">{p.version}</span>
                {!p.manager && (
                  <Switch checked={enabled} title={enabled ? "Désactiver" : "Activer"} onChange={(v) => cmd("enable", { id: p.id, enabled: v })} />
                )}
              </div>
              <div className={cx("my-1 text-xs", p.status === "ERROR" ? "text-danger" : "text-muted")}>{statusText(p)}</div>
              <div className="-ml-2 flex flex-wrap gap-0.5" onClick={(e) => e.stopPropagation()}>
                {enabled && <Button size="sm" variant="text" onClick={() => cmd("activate", { id: p.id })}>Ouvrir sur l'APC</Button>}
                <Button size="sm" variant="text" onClick={() => cmd("reload", { id: p.id })}>Recharger</Button>
                {!p.builtin && (
                  <Button
                    size="sm"
                    variant="text"
                    className="text-danger"
                    onClick={() => {
                      if (confirm(`Supprimer ${p.name} ?\nLa configuration et les données sont conservées.`)) void cmd("uninstall", { id: p.id });
                    }}
                  >
                    Supprimer
                  </Button>
                )}
              </div>
            </Card>
          );
        })}
      </div>
    </aside>
  );
}

function Card({ selected, foreground, onClick, dataId, children }: { selected: boolean; foreground?: boolean; onClick: () => void; dataId: string; children: ReactNode }) {
  return (
    <div
      data-id={dataId}
      onClick={onClick}
      className={cx(
        "card cursor-pointer rounded-xl border px-3 py-2.5",
        selected ? "selected bg-[#4a4458]" : "bg-surface-2/50",
        foreground ? "border-accent" : "border-transparent",
      )}
    >
      {children}
    </div>
  );
}
