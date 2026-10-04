import { useEffect, useRef, useState } from "react";
import jsQR from "jsqr";
import { Banner, Button, Section, Switch, cx } from "@apcdeck/web";
import { cmd } from "./api";
import type { Remote } from "./types";

/** Contrôle à distance : piloter ce PC depuis un mobile, ou piloter un autre PC depuis cet appareil. */
export function RemoteView({ remote }: { remote: Remote | null }) {
  if (!remote) return <p className="p-8 text-muted">Chargement…</p>;
  return (
    <div className="flex max-w-3xl flex-col gap-7 p-4">
      <h2 className="m-0 text-lg font-semibold">Contrôle à distance</h2>
      <p className="m-0 text-[13px] text-muted">
        Un mobile sur le même réseau Wi-Fi peut servir d'APC à ce PC : il affiche ses LED et lui envoie ce qui est joué (APC
        virtuel sur l'écran du mobile, ou APC branché au mobile). La connexion est chiffrée et n'accepte que les appareils
        appairés avec le QR code.
      </p>
      <DeviceName name={remote.deviceName} />
      <ServerSection server={remote.server} />
      <ClientSection client={remote.client} />
    </div>
  );
}

function DeviceName({ name }: { name: string }) {
  const [value, setValue] = useState(name);
  useEffect(() => setValue(name), [name]);
  return (
    <label className="flex flex-wrap items-center gap-2 text-[13px]">
      Nom de cet appareil
      <input
        value={value}
        maxLength={40}
        onChange={(e) => setValue(e.target.value)}
        onBlur={() => value.trim() !== name && cmd("remoteName", { name: value })}
        onKeyDown={(e) => e.key === "Enter" && e.currentTarget.blur()}
        className="min-w-0 flex-1 rounded-lg border border-[#938f99] bg-surface px-2 py-1.5 text-text"
      />
    </label>
  );
}

// --- ce PC, piloté par des mobiles --------------------------------------------------------------

function ServerSection({ server }: { server: Remote["server"] }) {
  return (
    <Section title="Piloter cet appareil depuis un mobile">
      <div className="flex flex-wrap items-center gap-3">
        <Switch checked={server.enabled} onChange={(v) => cmd("remoteEnable", { enabled: v })} title="Contrôle à distance" />
        <span className="text-[13px]">
          {server.running ? `Activé : en écoute sur le réseau local (port ${server.port})` : server.enabled ? "Démarrage…" : "Désactivé"}
        </span>
        {server.running && !server.pairing && <Button variant="primary" onClick={() => cmd("remotePair")}>Appairer un mobile…</Button>}
      </div>
      {server.error && <Banner tone="error">{server.error}</Banner>}
      {server.running && server.pairing && <PairingCard pairing={server.pairing} />}
      {server.devices.length > 0 && (
        <div className="flex flex-col gap-1.5">
          <span className="text-xs text-muted">Appareils autorisés</span>
          {server.devices.map((d) => (
            <div key={d.fingerprint} data-device={d.name} className="flex items-center gap-2.5 rounded-lg bg-surface-2 px-3 py-2">
              <span className={cx("h-2.5 w-2.5 shrink-0 rounded-full", d.connected ? "bg-ok" : "bg-surface-3")} />
              <span className="min-w-0 flex-1 truncate">
                <b>{d.name}</b>
                <span className="ml-2 text-xs text-muted">
                  {d.connected ? `connecté (${d.address})` : d.lastSeen ? `vu le ${new Date(d.lastSeen).toLocaleString()}` : "jamais connecté"}
                </span>
              </span>
              <span className="font-mono text-[11px] text-muted" title="Empreinte de sa clé">{d.fingerprint.slice(0, 8)}</span>
              <Button
                size="sm"
                variant="danger"
                onClick={() => window.confirm(`Retirer l'autorisation de ${d.name} ? Il devra être appairé à nouveau.`) && cmd("remoteRevoke", { fingerprint: d.fingerprint })}
              >
                Retirer
              </Button>
            </div>
          ))}
        </div>
      )}
      <p className="m-0 text-xs text-muted">
        Empreinte de ce PC : <span className="font-mono">{server.fingerprint.slice(0, 16)}…</span> · Au premier usage, le
        pare-feu peut demander d'autoriser APC Deck sur les réseaux privés.
      </p>
    </Section>
  );
}

function PairingCard({ pairing }: { pairing: NonNullable<Remote["server"]["pairing"]> }) {
  const left = useCountdown(pairing.expiresAt);
  const expired = left <= 0;
  return (
    <div className="flex flex-wrap items-start gap-4 rounded-xl bg-surface-2 p-4">
      <svg
        viewBox={`0 0 ${pairing.qrSize} ${pairing.qrSize}`}
        className={cx("h-56 w-56 shrink-0 rounded-lg bg-white", expired && "opacity-15")}
        shapeRendering="crispEdges"
        aria-label="QR code d'appairage"
      >
        <path d={pairing.qrPath} fill="#000" />
      </svg>
      <div className="flex min-w-0 flex-1 flex-col gap-2 text-[13px]">
        <b>Scanne ce QR code avec l'application APC Deck du mobile</b>
        <span className="text-muted">
          (Contrôle à distance → Scanner le QR code d'un PC.) Le code ne sert qu'à un seul appareil et contient une clé à usage
          unique : ne le partage pas.
        </span>
        <span className={cx("tabular-nums", expired && "text-danger")}>
          {expired ? "Code expiré." : `Valable encore ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`}
        </span>
        <span className="text-xs text-muted">Adresses : {pairing.hosts.join(", ")}</span>
        <details className="text-xs text-muted">
          <summary className="cursor-pointer">Pas de caméra ? Copier le lien d'appairage</summary>
          <code data-pairing-link className="mt-1 block break-all select-all">{pairing.link}</code>
        </details>
        <div className="flex gap-2">
          {expired && <Button variant="primary" onClick={() => cmd("remotePair")}>Nouveau code</Button>}
          <Button onClick={() => cmd("remoteCancelPairing")}>{expired ? "Fermer" : "Annuler"}</Button>
        </div>
      </div>
    </div>
  );
}

function useCountdown(until: number): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return Math.max(0, Math.round((until - now) / 1000));
}

// --- cet appareil, pilotant un PC ---------------------------------------------------------------

const STATE_LABEL: Record<Remote["client"]["state"], string> = {
  IDLE: "Non connecté",
  CONNECTING: "Connexion…",
  CONNECTED: "Connecté",
  ERROR: "Échec",
};

function ClientSection({ client }: { client: Remote["client"] }) {
  const [scanning, setScanning] = useState(false);
  const [link, setLink] = useState("");
  const pair = (text: string) => {
    setScanning(false);
    setLink("");
    void cmd("remotePairWith", { link: text });
  };
  const active = client.state !== "IDLE";
  return (
    <Section title="Piloter un PC depuis cet appareil">
      {active && (
        <div className="flex flex-wrap items-center gap-2.5 rounded-lg bg-surface-2 px-3 py-2">
          <span className={cx("h-2.5 w-2.5 rounded-full", client.state === "CONNECTED" ? "bg-ok" : client.state === "ERROR" ? "bg-ko" : "bg-warn")} />
          <span className="min-w-0 flex-1 text-[13px]">
            <b>{STATE_LABEL[client.state]}</b> {client.serverName && `· ${client.serverName}`} {client.address && <span className="text-muted">({client.address})</span>}
            {client.state === "CONNECTED" && <span className="block text-xs text-muted">L'APC de cet appareil (virtuel ou branché) pilote ce PC.</span>}
            {client.error && <span className="block text-xs text-danger">{client.error}</span>}
          </span>
          <Button size="sm" onClick={() => cmd("remoteDisconnect")}>{client.state === "ERROR" ? "Fermer" : "Déconnecter"}</Button>
        </div>
      )}
      {client.servers.length > 0 && (
        <div className="flex flex-col gap-1.5">
          <span className="text-xs text-muted">PC appairés</span>
          {client.servers.map((s) => {
            const current = client.server === s.fingerprint && active;
            return (
              <div key={s.fingerprint} data-server={s.name} className="flex items-center gap-2.5 rounded-lg bg-surface-2 px-3 py-2">
                <span className="min-w-0 flex-1 truncate">
                  <b>{s.name}</b> <span className="ml-1 text-xs text-muted">{s.hosts[0]}:{s.port}</span>
                </span>
                {!current && <Button size="sm" variant="primary" onClick={() => cmd("remoteConnect", { fingerprint: s.fingerprint })}>Connecter</Button>}
                <Button size="sm" variant="danger" onClick={() => window.confirm(`Oublier ${s.name} ?`) && cmd("remoteForget", { fingerprint: s.fingerprint })}>
                  Oublier
                </Button>
              </div>
            );
          })}
        </div>
      )}
      {scanning ? (
        <QrScanner onResult={pair} onClose={() => setScanning(false)} />
      ) : (
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" onClick={() => setScanning(true)}>Scanner le QR code d'un PC…</Button>
        </div>
      )}
      <form
        className="flex flex-wrap items-center gap-2 text-[13px]"
        onSubmit={(e) => {
          e.preventDefault();
          if (link.trim()) pair(link.trim());
        }}
      >
        <input
          value={link}
          onChange={(e) => setLink(e.target.value)}
          placeholder="ou colle le lien d'appairage (apcdeck://pair?…)"
          className="min-w-0 flex-1 rounded-lg border border-[#938f99] bg-surface px-2 py-1.5 text-text"
        />
        <Button type="submit" disabled={!link.trim()}>Appairer</Button>
      </form>
    </Section>
  );
}

/** Caméra + décodage jsQR, jusqu'au premier QR code lu. */
function QrScanner({ onResult, onClose }: { onResult: (text: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  // Le parent se redessine à chaque mise à jour de l'état : la caméra ne doit pas redémarrer pour autant.
  const resultRef = useRef(onResult);
  resultRef.current = onResult;
  useEffect(() => {
    let stream: MediaStream | null = null;
    let frame = 0;
    let done = false;
    const canvas = document.createElement("canvas");
    const context = canvas.getContext("2d", { willReadFrequently: true });
    const scan = () => {
      const v = video.current;
      if (done || !v || !context) return;
      if (v.readyState >= 2 && v.videoWidth > 0) {
        // Image réduite : le décodage reste fluide sur un téléphone.
        const scale = Math.min(1, 640 / Math.max(v.videoWidth, v.videoHeight));
        canvas.width = Math.round(v.videoWidth * scale);
        canvas.height = Math.round(v.videoHeight * scale);
        context.drawImage(v, 0, 0, canvas.width, canvas.height);
        const image = context.getImageData(0, 0, canvas.width, canvas.height);
        const code = jsQR(image.data, image.width, image.height, { inversionAttempts: "dontInvert" });
        if (code?.data.startsWith("apcdeck://")) {
          done = true;
          resultRef.current(code.data);
          return;
        }
      }
      frame = requestAnimationFrame(scan);
    };
    (async () => {
      try {
        if (!navigator.mediaDevices?.getUserMedia) throw new Error("caméra indisponible sur cet appareil");
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false });
        if (done) return;
        const v = video.current!;
        v.srcObject = stream;
        await v.play();
        frame = requestAnimationFrame(scan);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      done = true;
      cancelAnimationFrame(frame);
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, []);
  return (
    <div className="flex flex-col gap-2">
      {error ? (
        <Banner tone="error">Caméra impossible à ouvrir : {error}. Colle le lien d'appairage à la place.</Banner>
      ) : (
        <div className="relative w-full max-w-sm overflow-hidden rounded-xl bg-black">
          <video ref={video} playsInline muted className="block w-full" />
          <div className="pointer-events-none absolute inset-[15%] rounded-xl border-2 border-accent/80" />
        </div>
      )}
      <span className="text-xs text-muted">Vise le QR code affiché par le PC (Contrôle à distance → Appairer un mobile).</span>
      <div>
        <Button onClick={onClose}>Annuler</Button>
      </div>
    </div>
  );
}
