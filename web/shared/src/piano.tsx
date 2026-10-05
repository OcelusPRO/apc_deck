import { pressHandlers } from "./press";
import { cx } from "./ui";

const NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"] as const;
export const isBlackKey = (note: number): boolean => [1, 3, 6, 8, 10].includes(note % 12);
export const noteName = (note: number): string => `${NAMES[note % 12]}${Math.floor(note / 12) - 1}`;

/**
 * Clavier de piano à la largeur disponible. Un appui envoie (note, true) puis (note, false) au relâchement
 * (souris ou doigts, plusieurs notes à la fois en tactile).
 */
export function Piano({
  low,
  high,
  pressed,
  onPress,
  height = 84,
}: {
  low: number;
  high: number;
  pressed: ReadonlySet<number>;
  onPress: (note: number, down: boolean) => void;
  height?: number;
}) {
  const whites: number[] = [];
  for (let n = low; n <= high; n++) if (!isBlackKey(n)) whites.push(n);
  const w = 100 / whites.length;

  const down = (note: number) => pressHandlers((pressed) => onPress(note, pressed));

  return (
    <div className="relative select-none" style={{ height }}>
      {whites.map((note, i) => (
        <div
          key={note}
          title={`${noteName(note)} (${note})`}
          {...down(note)}
          className={cx("absolute top-0 cursor-pointer rounded-b-sm border border-black/60", pressed.has(note) ? "bg-accent" : "bg-[#ededed]")}
          style={{ left: `${i * w}%`, width: `${w}%`, height, touchAction: "none" }}
        >
          {note % 12 === 0 && (
            <span className="absolute bottom-0.5 left-1/2 -translate-x-1/2 text-[9px] whitespace-nowrap text-neutral-600">{noteName(note)}</span>
          )}
        </div>
      ))}
      {whites.map((white, i) => {
        const black = white + 1;
        if (!isBlackKey(black) || black > high) return null;
        return (
          <div
            key={black}
            title={`${noteName(black)} (${black})`}
            {...down(black)}
            className={cx("absolute top-0 z-10 cursor-pointer rounded-b-sm border border-black/60", pressed.has(black) ? "bg-accent" : "bg-[#1c1c1f]")}
            style={{ left: `${(i + 1) * w - w * 0.31}%`, width: `${w * 0.62}%`, height: height * 0.62, touchAction: "none" }}
          />
        );
      })}
    </div>
  );
}
