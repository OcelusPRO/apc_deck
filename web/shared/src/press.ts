import type { PointerEvent } from "react";

/**
 * Appui puis relâchement (comme un vrai bouton), à la souris, au stylet ou au doigt : (true) à l'appui,
 * (false) au relâchement. Chaque doigt est suivi séparément (multi-touch : plusieurs pads à la fois) ; le
 * relâchement arrive même si le doigt a glissé hors de l'élément (capture du pointeur).
 */
export function pressHandlers(onPress: (down: boolean) => void) {
  return {
    onPointerDown: (e: PointerEvent<HTMLElement>) => {
      if (e.pointerType === "mouse" && e.button !== 0) return;
      e.preventDefault();
      const el = e.currentTarget;
      const id = e.pointerId;
      try {
        el.setPointerCapture(id);
      } catch {
        // pointeur déjà relâché : le relâchement arrive tout de suite
      }
      onPress(true);
      const up = (ev: globalThis.PointerEvent) => {
        if (ev.pointerId !== id) return;
        window.removeEventListener("pointerup", up);
        window.removeEventListener("pointercancel", up);
        onPress(false);
      };
      window.addEventListener("pointerup", up);
      window.addEventListener("pointercancel", up);
    },
    // Pas de menu contextuel ni de sélection sur un appui long (tactile).
    onContextMenu: (e: { preventDefault: () => void }) => e.preventDefault(),
    style: { touchAction: "none" } as const,
  };
}
