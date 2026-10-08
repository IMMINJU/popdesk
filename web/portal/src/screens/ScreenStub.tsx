export interface ScreenStubProps {
  /** Screen ID from design/spec/screens.md, e.g. `P-1`. */
  id: string;
  title: string;
}

/** Placeholder until the screen slice fills it in. Shows the screen ID and title only. */
export function ScreenStub(props: ScreenStubProps) {
  return (
    <h1 tabIndex={-1}>
      {props.id} {props.title}
    </h1>
  );
}
