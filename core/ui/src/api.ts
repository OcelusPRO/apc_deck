import { useEffect, useState, useSyncExternalStore } from "react";
import type { AppState, LogLine } from "./types";

/** "/<jeton>/" : la page est servie à la racine du jeton. */
export const BASE = location.pathname.replace(/[^/]*$/, "");

// --- notifications -----------------------------------------------------------------------------

export interface Toast {
  id: number;
  message: string;
  error: boolean;
}

let toasts: Toast[] = [];
let nextToast = 1;
const toastListeners = new Set<() => void>();

export function toast(message: string, error = false): void {
  const id = nextToast++;
  toasts = [...toasts, { id, message, error }];
  toastListeners.forEach((l) => l());
  setTimeout(() => {
    toasts = toasts.filter((t) => t.id !== id);
    toastListeners.forEach((l) => l());
  }, error ? 6000 : 3000);
}

export function useToasts(): Toast[] {
  return useSyncExternalStore(
    (l) => {
      toastListeners.add(l);
      return () => toastListeners.delete(l);
    },
    () => toasts,
  );
}

// --- commandes ---------------------------------------------------------------------------------

async function errorOf(response: Response): Promise<string> {
  const text = await response.text();
  try {
    return (JSON.parse(text) as { error?: string }).error ?? text;
  } catch {
    return text;
  }
}

/** POST /<jeton>/app/cmd/<nom> ; une erreur s'affiche en notification. */
export async function cmd(name: string, data: unknown = {}): Promise<void> {
  try {
    const response = await fetch(`${BASE}app/cmd/${name}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });
    if (!response.ok) toast(await errorOf(response), true);
  } catch (e) {
    toast(`Application injoignable : ${e instanceof Error ? e.message : e}`, true);
  }
}

/** Envoie des jars (glisser-déposer ou sélecteur) ; le cœur les valide avant de répondre. */
export async function uploadJars(files: Iterable<File>): Promise<void> {
  for (const file of files) {
    if (!file.name.toLowerCase().endsWith(".jar")) {
      toast(`${file.name} : un fichier .jar est attendu`, true);
      continue;
    }
    try {
      const response = await fetch(`${BASE}app/cmd/install?name=${encodeURIComponent(file.name)}`, { method: "POST", body: file });
      if (!response.ok) {
        toast(await errorOf(response), true);
        continue;
      }
      const r = (await response.json()) as { name: string; version: string; previousVersion: string | null };
      if (!r.previousVersion) toast(`${r.name} ${r.version} installé`);
      else if (r.previousVersion !== r.version) toast(`${r.name} mis à jour (${r.previousVersion} → ${r.version})`);
      else toast(`${r.name} ${r.version} remplacé par le jar déposé`);
    } catch (e) {
      toast(`${file.name} : ${e instanceof Error ? e.message : e}`, true);
    }
  }
}

// --- état en direct ----------------------------------------------------------------------------

const EMPTY: AppState = {
  plugins: [],
  device: { connected: false, detail: "Connexion…" },
  settings: null,
  leds: null,
  input: null,
  learning: null,
  pager: null,
  logs: [],
};

/** État de l'application, alimenté par le flux SSE /<jeton>/app/events. */
export function useAppState(): AppState {
  const [state, setState] = useState<AppState>(EMPTY);
  useEffect(() => {
    const source = new EventSource(`${BASE}app/events`);
    source.onmessage = (message) => {
      const { event, data } = JSON.parse(message.data as string) as { event: string; data: unknown };
      setState((s) => {
        switch (event) {
          case "log":
            return { ...s, logs: [...s.logs, data as LogLine].slice(-500) };
          case "logs":
          case "plugins":
          case "device":
          case "settings":
          case "leds":
          case "input":
          case "learning":
          case "pager":
            return { ...s, [event]: data };
          default:
            return s;
        }
      });
    };
    source.onerror = () =>
      setState((s) => ({ ...s, device: { connected: false, detail: "Application injoignable, nouvelle tentative…" } }));
    return () => source.close();
  }, []);
  return state;
}

/** Appui puis relâchement (comme un vrai bouton) : (true) au clic, (false) au relâchement de la souris. */
export function pressHandlers(onPress: (down: boolean) => void) {
  return {
    onMouseDown: (e: { button: number; preventDefault: () => void }) => {
      if (e.button !== 0) return;
      e.preventDefault();
      onPress(true);
      const up = () => {
        onPress(false);
        window.removeEventListener("mouseup", up);
      };
      window.addEventListener("mouseup", up);
    },
  };
}
