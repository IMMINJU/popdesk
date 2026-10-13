import { renderMarkdown } from '../markdown';

export interface MarkdownViewProps {
  /** Markdown source. Rendered through an allow list (COM-6), never raw HTML. */
  source: string;
  /**
   * 이미지 (slice 5): the address of an uploaded image, by the lower-case UUID in
   * `![설명](attachment:{id})`. `null` leaves that image as text. Absent: every image syntax
   * stays text, as before. No other image syntax is ever drawn (D-59).
   */
  imageSrc?: ((id: string) => string | null) | undefined;
  /** Shown with the description when an image cannot be read. Default "이미지를 볼 수 없습니다". */
  unreadableImageText?: string | undefined;
}

/**
 * Read-only markdown. Links open in a new tab with `rel="noopener noreferrer"`.
 * Long code blocks scroll sideways. Images stay inside the width of the text.
 */
export function MarkdownView(props: MarkdownViewProps) {
  return (
    <div className="pd-markdown" data-pd="MarkdownView">
      {renderMarkdown(props.source, { imageSrc: props.imageSrc, unreadableImageText: props.unreadableImageText })}
    </div>
  );
}
