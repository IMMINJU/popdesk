export interface ScreenStubProps {
  /** Screen ID from design/spec/screens.md, e.g. `C-12`. */
  id: string;
  title: string;
}

/** Placeholder until C-9 fills the screen in. Shows the screen ID and title only. */
export function ScreenStub(props: ScreenStubProps) {
  return (
    <h1 tabIndex={-1}>
      {props.id} {props.title}
    </h1>
  );
}
