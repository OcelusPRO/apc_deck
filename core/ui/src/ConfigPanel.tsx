import { useState, type ReactNode } from "react";
import { APC_PALETTE, Button, Switch, cx } from "@apcdeck/web";
import { cmd } from "./api";
import type { AppState, BindingValue, ConfigField, PluginView } from "./types";

const input = "w-full rounded-lg border border-[#938f99] bg-surface px-2.5 py-1.5 text-text focus:border-transparent focus:outline-2 focus:outline-accent";

/** Configuration du plugin sélectionné : formulaire généré depuis son schéma (ConfigSpec). */
export function ConfigPanel({ plugin, learning, className }: { plugin: PluginView | null; learning: AppState["learning"]; className?: string }) {
  return (
    <aside id="config" className={cx("overflow-auto border-line p-4 lg:border-l", className)}>
      {!plugin ? (
        <>
          <h2 className="m-0 text-xl font-medium">APC Key 25 mk2</h2>
          <p className="text-muted">Sélectionne un plugin pour le configurer.</p>
        </>
      ) : (
        <>
          <h2 className="m-0 text-xl font-medium">{plugin.name}</h2>
          <div className="mt-1 mb-2.5 text-xs text-muted">
            {[`v${plugin.version}`, plugin.author && `par ${plugin.author}`, plugin.id].filter(Boolean).join(" · ")}
          </div>
          {plugin.description && <p>{plugin.description}</p>}
          <Button size="sm" onClick={() => cmd("openFolder", { id: plugin.id })}>Ouvrir le dossier de données</Button>
          <hr className="my-4 border-0 border-t border-line" />
          <h3 className="m-0 text-sm font-semibold">Configuration</h3>
          <div className="fields mt-3 flex flex-col gap-3.5">
            {plugin.status !== "ENABLED" ? (
              <p className="text-muted">Active le plugin pour accéder à sa configuration.</p>
            ) : plugin.fields.length === 0 ? (
              <p className="text-muted">Ce plugin n'a pas d'options.</p>
            ) : (
              plugin.fields.map((f) => (
                <FieldEditor
                  key={`${plugin.id}/${f.key}`}
                  plugin={plugin}
                  field={f}
                  value={plugin.config[f.key]}
                  listening={learning?.pluginId === plugin.id && learning.fieldKey === f.key}
                />
              ))
            )}
          </div>
        </>
      )}
    </aside>
  );
}

function FieldEditor({ plugin, field: f, value, listening }: { plugin: PluginView; field: ConfigField; value: unknown; listening: boolean }) {
  const set = (v: unknown) => cmd("config", { id: plugin.id, key: f.key, value: v });
  const help = f.description ? <div className="text-xs text-muted">{f.description}</div> : null;

  switch (f.type) {
    case "bool":
      return (
        <div className="field flex flex-col gap-1.5">
          <div className="flex items-center gap-2">
            <span className="flex-1">{f.label}</span>
            <Switch checked={Boolean(value)} onChange={set} />
          </div>
          {help}
        </div>
      );
    case "int":
      if (f.min !== undefined && f.max !== undefined && f.max - f.min <= 100) return <IntSlider field={f} value={Number(value)} onChange={set} help={help} />;
      return (
        <label className="field flex flex-col gap-1.5">
          {f.label}
          <input type="number" step={1} defaultValue={Number(value)} className={input} onChange={(e) => set(parseInt(e.target.value, 10))} />
          {help}
        </label>
      );
    case "decimal":
      return (
        <label className="field flex flex-col gap-1.5">
          {f.label}
          <input type="number" step="any" defaultValue={Number(value)} className={input} onChange={(e) => set(parseFloat(e.target.value))} />
          {help}
        </label>
      );
    case "text":
      return (
        <label className="field flex flex-col gap-1.5">
          {f.label}
          {f.multiline ? (
            <textarea rows={4} defaultValue={String(value ?? "")} className={input} onBlur={(e) => set(e.target.value)} />
          ) : (
            <input defaultValue={String(value ?? "")} className={input} onBlur={(e) => set(e.target.value)} onKeyDown={(e) => e.key === "Enter" && e.currentTarget.blur()} />
          )}
          {help}
        </label>
      );
    case "choice":
      return (
        <label className="field flex flex-col gap-1.5">
          {f.label}
          <select value={String(value)} className={input} onChange={(e) => set(e.target.value)}>
            {(f.options ?? []).map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
          {help}
        </label>
      );
    case "color":
      return (
        <div className="field flex flex-col gap-1.5">
          <span>{f.label} · index {String(value)}</span>
          <div className="grid grid-cols-16 gap-[3px]">
            {APC_PALETTE.map((hex, i) => (
              <button
                key={i}
                type="button"
                title={`Couleur ${i}`}
                onClick={() => set(i)}
                className={cx("aspect-square cursor-pointer rounded-[3px] border border-line", i === value && "outline-2 outline-offset-1 outline-white")}
                style={{ background: i ? hex : "var(--color-pad-off)" }}
              />
            ))}
          </div>
          {help}
        </div>
      );
    case "binding": {
      const binding = value as BindingValue | undefined;
      const none = !binding || binding.encoded === "";
      return (
        <div className="field flex flex-col gap-1.5">
          <span>{f.label}</span>
          <div className="flex items-center gap-2">
            <div
              className={cx(
                "binding flex-1 rounded-lg border px-3 py-2",
                listening ? "listening border-accent bg-accent/15 text-accent" : "border-transparent bg-surface-2",
                !listening && none && "none text-muted",
              )}
            >
              {listening ? "Appuie sur l'APC…" : (binding?.label ?? "Non assignée")}
            </div>
            {listening ? (
              <Button variant="text" onClick={() => cmd("cancelLearn")}>Annuler</Button>
            ) : (
              <Button onClick={() => cmd("learn", { id: plugin.id, key: f.key })}>Assigner</Button>
            )}
            {!listening && !none && <Button variant="text" title="Retirer" onClick={() => set("")}>✕</Button>}
          </div>
          {help}
        </div>
      );
    }
  }
}

function IntSlider({ field, value, onChange, help }: { field: ConfigField; value: number; onChange: (v: number) => void; help: ReactNode }) {
  const [local, setLocal] = useState<number | null>(null);
  return (
    <label className="field flex flex-col gap-1.5">
      <span className="flex justify-between">
        {field.label} <span className="text-accent">{local ?? value}</span>
      </span>
      <input
        type="range"
        min={field.min}
        max={field.max}
        step={1}
        value={local ?? value}
        onChange={(e) => setLocal(Number(e.target.value))}
        onMouseUp={() => {
          if (local !== null) onChange(local);
          setLocal(null);
        }}
      />
      {help}
    </label>
  );
}
