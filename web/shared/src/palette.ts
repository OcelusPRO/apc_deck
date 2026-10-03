/** Approximation RGB des 128 couleurs de la palette de l'APC (index = vélocité MIDI du pad). */
export const APC_PALETTE: readonly string[] = [
  "#000000", "#1e1e1e", "#7f7f7f", "#ffffff", "#ff4c4c", "#ff0000", "#590000", "#190000",
  "#ffbd6c", "#ff5400", "#591d00", "#271b00", "#ffff4c", "#ffff00", "#595900", "#191900",
  "#88ff4c", "#54ff00", "#1d5900", "#142b00", "#4cff4c", "#00ff00", "#005900", "#001900",
  "#4cff5e", "#00ff19", "#00590d", "#001902", "#4cff88", "#00ff55", "#00591d", "#001f12",
  "#4cffb7", "#00ff99", "#005935", "#001912", "#4cc3ff", "#00a9ff", "#004152", "#001019",
  "#4c88ff", "#0055ff", "#001d59", "#000819", "#4c4cff", "#0000ff", "#000059", "#000019",
  "#874cff", "#5400ff", "#190064", "#0f0030", "#ff4cff", "#ff00ff", "#590059", "#190019",
  "#ff4c87", "#ff0054", "#59001d", "#220013", "#ff1500", "#993500", "#795100", "#436400",
  "#033900", "#005735", "#00547f", "#0000ff", "#00454f", "#2500cc", "#7f7f7f", "#202020",
  "#ff0000", "#bdff2d", "#afed06", "#64ff09", "#108b00", "#00ff87", "#00a9ff", "#002aff",
  "#3f00ff", "#7a00ff", "#b21a7d", "#402100", "#ff4a00", "#88e106", "#72ff15", "#00ff00",
  "#3bff26", "#59ff71", "#38ffcc", "#5b8aff", "#3151c6", "#877fe9", "#d31dff", "#ff005d",
  "#ff7f00", "#b9b000", "#90ff00", "#835d07", "#392b00", "#144c10", "#0d5038", "#15152a",
  "#16205a", "#693c1c", "#a8000a", "#de513d", "#d86a1c", "#ffe126", "#9ee12f", "#67b50f",
  "#1e1e30", "#dcff6b", "#80ffbd", "#9a99ff", "#8e66ff", "#404040", "#757575", "#e0ffff",
  "#a00000", "#350000", "#1ad000", "#074200", "#b9b000", "#3f3100", "#b35f00", "#4b1502",
];

export const padHex = (index: number): string => APC_PALETTE[Math.max(0, Math.min(127, index))] ?? "#000000";

/** Texte lisible (noir ou blanc) sur une couleur. */
export function textOn(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  return (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255 > 0.55 ? "#111111" : "#ffffff";
}
