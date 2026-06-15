// Ported from the BookForge authors' site (frontend/src/palettes.js) so the
// admin console offers the same four palettes. The authors' site uses a richer
// var set (--surface/--ink/--line/--success/--warning/--danger); the admin
// console uses a leaner one (--panel/--text/--border/--good/--warn/--bad), so
// each palette is mapped onto the admin scheme below. All values are CSS custom
// properties, so every screen recolors live.
//
// Mapping (authors -> admin): surface->panel, surface-2->panel-2, ink->text,
// line->border, success->good, warning->warn, danger->bad (bg/muted/accent same).

export const palettes = {
  dark: {
    name: 'Forge dark',
    tagline: 'Late-night ops, ember accents.',
    swatches: ['#0B0A09', '#EFE7D8', '#E8923C'],
    vars: {
      '--bg': '#0B0A09',
      '--panel': '#181410',
      '--panel-2': '#13110E',
      '--border': '#2A2520',
      '--text': '#EFE7D8',
      '--muted': '#CABFA9',
      '--accent': '#E8923C',
      '--good': '#7DB89A',
      '--warn': '#E8923C',
      '--bad': '#D87A6E',
    },
  },

  dusk: {
    name: 'Dusk',
    tagline: 'Quiet evening reading, premium feel.',
    swatches: ['#0F1B2D', '#F4EFE6', '#D89B4A'],
    vars: {
      '--bg': '#0F1B2D',
      '--panel': '#172642',
      '--panel-2': '#1F3050',
      '--border': '#2A3A5B',
      '--text': '#F4EFE6',
      '--muted': '#A8B2C5',
      '--accent': '#D89B4A',
      '--good': '#7DB89A',
      '--warn': '#D89B4A',
      '--bad': '#D87A6E',
    },
  },

  press: {
    name: 'Press',
    tagline: 'Newspaper of record, no-nonsense.',
    swatches: ['#FFFFFF', '#0A0A0A', '#C8102E'],
    vars: {
      '--bg': '#FAFAF7',
      '--panel': '#FFFFFF',
      '--panel-2': '#F2F2EE',
      '--border': '#E2E2DD',
      '--text': '#0A0A0A',
      '--muted': '#4A4A48',
      '--accent': '#C8102E',
      '--good': '#2D6B3A',
      '--warn': '#B8741D',
      '--bad': '#C8102E',
    },
  },

  studio: {
    name: 'Studio',
    tagline: 'Modern design studio, confident.',
    swatches: ['#F4F1EC', '#1C1C1A', '#FF5A36'],
    vars: {
      '--bg': '#F4F1EC',
      '--panel': '#FCFAF6',
      '--panel-2': '#E8E4DC',
      '--border': '#D8D4CC',
      '--text': '#1C1C1A',
      '--muted': '#52524E',
      '--accent': '#FF5A36',
      '--good': '#1F6F4A',
      '--warn': '#C77A1F',
      '--bad': '#FF5A36',
    },
  },
};

export const paletteList = Object.entries(palettes).map(([id, p]) => ({ id, ...p }));
