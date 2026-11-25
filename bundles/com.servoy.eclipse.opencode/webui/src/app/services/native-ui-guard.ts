const isSideButton = (event: MouseEvent): boolean => event.button === 3 || event.button === 4;

const isMac = (): boolean => /mac/i.test(globalThis.navigator?.platform ?? '');

const isEditable = (target: EventTarget | null): boolean => {
  if (!(target instanceof Element)) {
    return false;
  }
  return target.matches('textarea, input, [contenteditable]:not([contenteditable="false"])');
};

export function installNativeUiGuard(target: Document = document): () => void {
  const onContextMenu = (event: Event): void => event.preventDefault();

  const onSideButton = (event: Event): void => {
    if (isSideButton(event as MouseEvent)) {
      event.preventDefault();
    }
  };

  const onKeyDown = (event: KeyboardEvent): void => {
    const historyKey = event.key === 'ArrowLeft' || event.key === 'ArrowRight';
    if (!historyKey || !event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
      return;
    }
    if (isMac() && isEditable(event.target)) {
      return;
    }
    event.preventDefault();
  };

  const sideButtonEvents = ['mousedown', 'mouseup', 'auxclick', 'pointerup'];

  target.addEventListener('contextmenu', onContextMenu, { passive: false });
  for (const name of sideButtonEvents) {
    target.addEventListener(name, onSideButton, { capture: true, passive: false });
  }
  target.addEventListener('keydown', onKeyDown, { passive: false });

  return () => {
    target.removeEventListener('contextmenu', onContextMenu);
    for (const name of sideButtonEvents) {
      target.removeEventListener(name, onSideButton, { capture: true });
    }
    target.removeEventListener('keydown', onKeyDown);
  };
}
