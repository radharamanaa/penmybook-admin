export const HOLD_NOTE_MIN_LENGTH = 8

// The lift buttons stay disabled until the note is long enough; this says why.
export function HoldNoteHint({ note }) {
  const length = note.trim().length
  if (length >= HOLD_NOTE_MIN_LENGTH) return null
  return <p className="sub">At least {HOLD_NOTE_MIN_LENGTH} characters ({length}/{HOLD_NOTE_MIN_LENGTH})</p>
}
