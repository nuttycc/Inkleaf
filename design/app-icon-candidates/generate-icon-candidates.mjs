import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const outDir = join(root, "out");
const contactSheetSize = {
  width: 788,
  height: 220,
};

const candidates = [
  {
    id: "a-leaf-page",
    name: "Leaf Page",
    bg: "#F7F4EF",
    fg: "#18322F",
    accent: "#4A7C59",
    shadow: "#D8E2D2",
    draw: ({ bg, fg, accent, shadow }) => `
      <path d="M25 31h42c8 0 14 6 14 14v32H37c-7 0-12-5-12-12V31Z" fill="${shadow}"/>
      <path d="M29 27h39c7 0 12 5 12 12v36H40c-6 0-11-5-11-11V27Z" fill="#FFFFFF"/>
      <path d="M38 36h28" stroke="${fg}" stroke-width="4" stroke-linecap="round"/>
      <path d="M38 47h22" stroke="${fg}" stroke-width="4" stroke-linecap="round"/>
      <path d="M61 74C77 61 78 44 70 34C53 40 43 51 42 69C49 66 55 64 62 61C57 66 53 70 49 75C53 76 57 76 61 74Z" fill="${accent}"/>
      <path d="M47 72C55 62 63 52 70 38" stroke="#F7F4EF" stroke-width="3" stroke-linecap="round"/>
    `,
  },
  {
    id: "b-panel-leaf",
    name: "Panel Leaf",
    bg: "#16302B",
    fg: "#F8F3E8",
    accent: "#E2B84A",
    shadow: "#2F5D50",
    draw: ({ bg, fg, accent, shadow }) => `
      <rect x="26" y="26" width="56" height="56" rx="10" fill="${fg}"/>
      <rect x="33" y="33" width="20" height="18" rx="3" fill="${shadow}"/>
      <rect x="57" y="33" width="18" height="18" rx="3" fill="${shadow}"/>
      <rect x="33" y="55" width="18" height="20" rx="3" fill="${shadow}"/>
      <rect x="55" y="55" width="20" height="20" rx="3" fill="${shadow}"/>
      <path d="M72 27C79 42 72 60 53 72C48 57 55 39 72 27Z" fill="${accent}"/>
      <path d="M55 69C61 57 66 45 71 32" stroke="${bg}" stroke-width="3" stroke-linecap="round"/>
    `,
  },
  {
    id: "c-folded-page",
    name: "Folded Page",
    bg: "#EAF5F0",
    fg: "#1F2D2B",
    accent: "#2F7F6F",
    shadow: "#BFD8CF",
    draw: ({ bg, fg, accent, shadow }) => `
      <path d="M31 24h34l13 13v47H31V24Z" fill="#FFFFFF"/>
      <path d="M65 24v13h13" fill="${shadow}"/>
      <path d="M31 24h34l13 13v47H31V24Z" fill="none" stroke="${fg}" stroke-width="5" stroke-linejoin="round"/>
      <path d="M42 44h23" stroke="${fg}" stroke-width="4" stroke-linecap="round"/>
      <path d="M42 56h17" stroke="${fg}" stroke-width="4" stroke-linecap="round"/>
      <path d="M61 79C73 69 74 56 68 48C55 53 48 62 47 76C52 74 56 72 61 70C57 74 54 77 51 81C55 82 58 82 61 79Z" fill="${accent}"/>
    `,
  },
  {
    id: "d-ink-drop",
    name: "Ink Drop",
    bg: "#F8F1E7",
    fg: "#191B1F",
    accent: "#5B7E60",
    shadow: "#D9CBB9",
    draw: ({ bg, fg, accent, shadow }) => `
      <path d="M54 21C69 39 78 51 78 65C78 79 68 88 54 88C40 88 30 79 30 65C30 51 39 39 54 21Z" fill="${fg}"/>
      <path d="M54 32C65 45 70 55 70 65C70 74 64 80 54 80C44 80 38 74 38 65C38 55 43 45 54 32Z" fill="${shadow}"/>
      <path d="M60 75C69 67 70 56 65 50C55 54 49 61 49 72C53 70 57 68 61 66C58 70 55 73 53 76C56 77 58 77 60 75Z" fill="${accent}"/>
    `,
  },
  {
    id: "e-shelf-mark",
    name: "Shelf Mark",
    bg: "#202B38",
    fg: "#F7F0DF",
    accent: "#79A36B",
    shadow: "#AEB7C2",
    draw: ({ bg, fg, accent, shadow }) => `
      <path d="M25 72h58" stroke="${fg}" stroke-width="7" stroke-linecap="round"/>
      <path d="M32 32h10v36H32V32Z" fill="${fg}"/>
      <path d="M48 28h10v40H48V28Z" fill="${shadow}"/>
      <path d="M64 35h10v33H64V35Z" fill="${fg}"/>
      <path d="M44 84C60 74 65 58 60 45C45 50 36 62 36 78C43 75 49 72 55 67C51 74 47 79 44 84Z" fill="${accent}"/>
      <path d="M39 79C47 69 53 59 59 47" stroke="${bg}" stroke-width="3" stroke-linecap="round"/>
    `,
  },
];

function svg(candidate) {
  const { bg, fg, accent, shadow } = candidate;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108" role="img" aria-labelledby="title">
  <title>Inkleaf app icon candidate ${candidate.name}</title>
  <rect width="108" height="108" rx="24" fill="${bg}"/>
  ${candidate.draw({ bg, fg, accent, shadow }).trim()}
</svg>
`;
}

function contactSheet(items) {
  const tileWidth = 148;
  const tileHeight = 172;
  const padding = 24;
  const width = padding * 2 + tileWidth * items.length;
  const height = padding * 2 + tileHeight;
  const tiles = items
    .map((candidate, index) => {
      const x = padding + index * tileWidth;
      const icon = svg(candidate)
        .replace('<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108" role="img" aria-labelledby="title">', `<svg x="${x + 20}" y="${padding + 12}" width="108" height="108" viewBox="0 0 108 108">`)
        .replace(/<title>.*?<\/title>\n  /, "");
      return `
        <rect x="${x}" y="${padding}" width="124" height="148" rx="14" fill="#FFFFFF" stroke="#DADDE3"/>
        ${icon.trim()}
        <text x="${x + 62}" y="${padding + 138}" text-anchor="middle" font-family="Inter, Arial, sans-serif" font-size="12" fill="#30343B">${candidate.name}</text>`;
    })
    .join("\n");

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
  <rect width="${width}" height="${height}" fill="#F5F6F8"/>
  ${tiles.trim()}
</svg>
`;
}

function previewHtml(items) {
  const cards = items
    .map((candidate) => `
      <article>
        <img src="out/inkleaf-icon-${candidate.id}.svg" alt="${candidate.name}">
        <h2>${candidate.name}</h2>
        <p>${candidate.id}</p>
      </article>`)
    .join("\n");

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Inkleaf App Icon Candidates</title>
  <style>
    body {
      margin: 0;
      background: #f5f6f8;
      color: #20242a;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    main {
      max-width: 920px;
      margin: 0 auto;
      padding: 32px 20px 44px;
    }
    h1 {
      margin: 0 0 20px;
      font-size: 24px;
      font-weight: 650;
      letter-spacing: 0;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
      gap: 16px;
    }
    article {
      background: #fff;
      border: 1px solid #dadde3;
      border-radius: 8px;
      padding: 16px;
    }
    img {
      width: 108px;
      height: 108px;
      display: block;
      margin: 0 auto 12px;
    }
    h2 {
      margin: 0;
      font-size: 14px;
      font-weight: 650;
      letter-spacing: 0;
      text-align: center;
    }
    p {
      margin: 4px 0 0;
      color: #626975;
      font-size: 11px;
      text-align: center;
    }
  </style>
</head>
<body>
  <main>
    <h1>Inkleaf App Icon Candidates</h1>
    <section class="grid">
${cards}
    </section>
  </main>
</body>
</html>
`;
}

function readme(items) {
  const list = items
    .map((candidate) => `- ${candidate.name}: \`out/inkleaf-icon-${candidate.id}.svg\``)
    .join("\n");

  return `# Inkleaf App Icon Candidates

Generated minimalist SVG candidates for the Inkleaf launcher icon.

Run:

\`\`\`powershell
node design/app-icon-candidates/generate-icon-candidates.mjs
\`\`\`

Outputs:

${list}
- Contact sheet: \`out/inkleaf-icon-contact-sheet.svg\`
- Browser preview: \`preview.html\`

These are design candidates only. They do not replace the current Android launcher resources.
`;
}

mkdirSync(outDir, { recursive: true });

for (const candidate of candidates) {
  writeFileSync(join(outDir, `inkleaf-icon-${candidate.id}.svg`), svg(candidate));
}

writeFileSync(join(outDir, "inkleaf-icon-contact-sheet.svg"), contactSheet(candidates));
writeFileSync(join(root, "preview.html"), previewHtml(candidates));
writeFileSync(join(root, "README.md"), readme(candidates));

console.log(`Generated ${candidates.length} icon candidates in ${outDir}`);
