import { useState } from 'react';

/**
 * An uploaded image in a MarkdownView. When it cannot be read, one line takes its
 * place: the description and `unreadableText`. No broken picture icon.
 */
export function MarkdownImage(props: { src: string; alt: string; unreadableText: string }) {
  const [failed, setFailed] = useState<string | null>(null);
  if (failed === props.src) {
    return (
      <span className="pd-markdown-noimage">
        {props.alt !== '' && (
          <>
            <span className="pd-markdown-noimage-alt">{props.alt}</span>{' '}
          </>
        )}
        <span>{props.unreadableText}</span>
      </span>
    );
  }
  return (
    <img
      src={props.src}
      alt={props.alt}
      loading="lazy"
      onError={() => {
        setFailed(props.src);
      }}
    />
  );
}
