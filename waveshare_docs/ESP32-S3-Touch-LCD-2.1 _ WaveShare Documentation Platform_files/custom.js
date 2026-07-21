/**
 * After the page loads, automatically scroll the sidebar to center the currently active menu item.
 */
window.onload = function () {
  const scroll = () => {
    const list = document.querySelectorAll(".menu__link--active");
    if (!list.length) return false;

    const savedY = window.scrollY;
    list[list.length - 1].scrollIntoView({ block: "center" });

    // Restore main content scroll position: jump to anchor if present, otherwise restore original position
    if (window.location.hash) {
      const id = decodeURIComponent(window.location.hash.slice(1));
      const anchor = document.getElementById(id);
      if (anchor) anchor.scrollIntoView();
    } else {
      window.scrollTo(0, savedY);
    }

    return true;
  };

  // Debounce: wait 150ms after DOM mutations settle before executing, to avoid being overwritten by subsequent framework renders
  let timer = null;
  const observer = new MutationObserver(() => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (scroll()) observer.disconnect();
    }, 150);
  });

  observer.observe(document.body, { childList: true, subtree: true });
  setTimeout(() => observer.disconnect(), 3000); // Fallback: force stop observing after 3s
};