export interface MarkdownViewProps {
  /** Markdown source. Rendered through an allow list (COM-6), never raw HTML. */
  source: string;
}

/**
 * Read-only markdown. Links open in a new tab with `rel="noopener noreferrer"`.
 * Long code blocks scroll sideways. The shell shows the source as plain text.
 */
export function MarkdownView(props: MarkdownViewProps) {
  return (
    <div data-pd="MarkdownView">
      <p>{props.source}</p>
    </div>
  );
}
