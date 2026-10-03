import { useRef } from "react";
import { Button } from "@apcdeck/web";
import { cmd } from "./api";
import type { PluginView } from "./types";

/** Page web d'un plugin, dans un cadre (même origine : le plugin parle à l'application via apcdeck.js). */
export function PluginPage({ plugin }: { plugin: PluginView }) {
  const frame = useRef<HTMLIFrameElement>(null);
  if (!plugin.webUrl) return null;
  return (
    <div className="flex h-full flex-col overflow-hidden">
      <div className="flex flex-none items-center gap-1 border-b border-line px-3 py-1">
        <h3 className="m-0 flex-1 text-sm font-semibold">{plugin.name}</h3>
        <Button size="sm" variant="text" onClick={() => cmd("openFolder", { id: plugin.id })}>Dossier de données</Button>
        <Button size="sm" variant="text" onClick={() => frame.current?.contentWindow?.location.reload()}>Recharger la page</Button>
        <Button size="sm" variant="text" onClick={() => window.open(plugin.webUrl ?? "", "_blank")}>Ouvrir dans un onglet</Button>
      </div>
      <iframe ref={frame} src={plugin.webUrl} title={plugin.name} className="plugin-frame w-full flex-1 border-0 bg-bg" />
    </div>
  );
}
