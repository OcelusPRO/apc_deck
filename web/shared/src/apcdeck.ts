import { useCallback, useEffect, useRef, useState } from "react";

/** Client injecté par l'application dans les pages de plugins (`<script src="/apcdeck.js">`). */
export interface ApcdeckClient {
  readonly id: string;
  call<T = unknown>(action: string, data?: unknown): Promise<T>;
  on(event: string, callback: (data: unknown) => void): void;
}

declare global {
  interface Window {
    apcdeck?: ApcdeckClient;
  }
}

export function apcdeck(): ApcdeckClient {
  const client = window.apcdeck;
  if (!client) throw new Error("apcdeck.js absent : la page doit être servie par l'application");
  return client;
}

/** Abonnement à un événement poussé par le plugin (`ctx.web.emit`). */
export function usePluginEvent<T>(event: string, handler: (data: T) => void): void {
  const ref = useRef(handler);
  ref.current = handler;
  useEffect(() => {
    apcdeck().on(event, (data) => ref.current(data as T));
  }, [event]);
}

/**
 * État complet d'un plugin dont les actions renvoient l'état (convention de Macros et Synthé) :
 * chargé par l'action "state", mis à jour par l'événement "state" et par chaque réponse d'action.
 */
export function usePluginState<S extends object>(): {
  state: S | null;
  call: (action: string, data?: unknown) => Promise<S | null>;
  error: string | null;
} {
  const [state, setState] = useState<S | null>(null);
  const [error, setError] = useState<string | null>(null);

  const call = useCallback(async (action: string, data?: unknown) => {
    try {
      const next = await apcdeck().call<S | null>(action, data);
      if (next && typeof next === "object") setState(next);
      setError(null);
      return next;
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return null;
    }
  }, []);

  usePluginEvent<S>("state", setState);
  useEffect(() => {
    void call("state");
  }, [call]);

  return { state, call, error };
}
