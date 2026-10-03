import type { ButtonHTMLAttributes, ReactNode } from "react";

/** Assemble des classes en ignorant les valeurs vides. */
export function cx(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(" ");
}

type Variant = "primary" | "outline" | "text" | "danger" | "stop";

const VARIANTS: Record<Variant, string> = {
  primary: "bg-accent text-on-accent border-accent",
  outline: "border-[#938f99] text-accent",
  text: "border-transparent text-accent",
  danger: "border-[#938f99] text-danger",
  stop: "bg-ok border-ok text-[#002106]",
};

export function Button({
  variant = "outline",
  size = "md",
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" | "md" }) {
  return (
    <button
      type="button"
      {...props}
      className={cx(
        "inline-flex items-center justify-center gap-1.5 rounded-full border whitespace-nowrap transition",
        "hover:brightness-115 disabled:opacity-40 disabled:cursor-default cursor-pointer",
        size === "sm" ? "px-2.5 py-1 text-xs" : "px-3.5 py-1.5",
        VARIANTS[variant],
        className,
      )}
    />
  );
}

/** Choix exclusif parmi quelques options (forme d'onde, mode…). */
export function Segmented<T extends string | number>({
  options,
  value,
  onChange,
}: {
  options: ReadonlyArray<{ id: T; label: ReactNode }>;
  value: T | null;
  onChange: (id: T) => void;
}) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {options.map((o) => (
        <Button key={String(o.id)} variant={o.id === value ? "primary" : "outline"} onClick={() => onChange(o.id)}>
          {o.label}
        </Button>
      ))}
    </div>
  );
}

/** Interrupteur on/off. */
export function Switch({ checked, onChange, title }: { checked: boolean; onChange: (v: boolean) => void; title?: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      title={title}
      onClick={(e) => {
        e.stopPropagation();
        onChange(!checked);
      }}
      className={cx(
        "relative h-5 w-9 shrink-0 rounded-full border transition cursor-pointer",
        checked ? "bg-accent border-accent" : "bg-surface-3 border-[#938f99]",
      )}
    >
      <span
        className={cx(
          "absolute top-[3px] left-[3px] h-3 w-3 rounded-full transition",
          checked ? "translate-x-4 bg-on-accent" : "bg-[#938f99]",
        )}
      />
    </button>
  );
}

export function Section({ title, hint, children, className }: { title: ReactNode; hint?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx("flex flex-col gap-2.5", className)}>
      <h2 className="m-0 text-sm font-semibold">
        {title} {hint && <span className="ml-1 text-xs font-normal text-muted">{hint}</span>}
      </h2>
      {children}
    </section>
  );
}

export function Banner({ tone = "info", children }: { tone?: "info" | "error"; children: ReactNode }) {
  return (
    <div className={cx("flex items-center gap-3 rounded-xl bg-surface-2 px-3.5 py-2.5", tone === "error" && "border-l-3 border-danger")}>
      {children}
    </div>
  );
}

/** Curseur 0..1 avec libellé et valeur affichée. */
export function Slider({
  label,
  display,
  value,
  min = 0,
  max = 1,
  step = 0.001,
  onChange,
}: {
  label: ReactNode;
  display: ReactNode;
  value: number;
  min?: number;
  max?: number;
  step?: number;
  onChange: (v: number) => void;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="flex justify-between text-[13px]">
        <span>{label}</span>
        <span className="text-accent tabular-nums">{display}</span>
      </span>
      <input type="range" min={min} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} className="w-full" />
    </label>
  );
}
