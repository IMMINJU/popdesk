import { renderMarkdown } from '../markdown';

export interface MarkdownViewProps {
  /** Markdown source. Rendered through an allow list (COM-6), never raw HTML. */
  source: string;
}

/**
 * Read-only markdown. Links open in a new tab with `rel="noopener noreferrer"`.
 * Long code blocks scroll sideways.
 */
export function MarkdownView(props: MarkdownViewProps) {
  return (
    <div className="pd-markdown" data-pd="MarkdownView">
      {renderMarkdown(props.source)}
    </div>
  );
}
