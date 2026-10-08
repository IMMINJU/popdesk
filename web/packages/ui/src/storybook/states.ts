// Helpers for state stories.
//
// Focus stories move focus in `play`. Hover and pressed are CSS states; their
// stories set `parameters.pseudo` in the shape storybook-addon-pseudo-states
// reads. That addon is not installed yet; C-9 decides.

/** `play` that focuses the first element matching `selector`. */
export function focusFirst(selector: string) {
  return ({ canvasElement }: { canvasElement: HTMLElement }) => {
    canvasElement.querySelector<HTMLElement>(selector)?.focus();
  };
}

export const hover = { pseudo: { hover: true } } as const;
export const pressed = { pseudo: { active: true } } as const;
