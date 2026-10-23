// xrnrule extension: the one setting it has, the port xrnrule listens on.

(async () => {
  const input = document.getElementById("port");
  const result = document.getElementById("result");
  const { port } = await chrome.storage.local.get("port");
  if (port) input.value = port;
  document.getElementById("save").addEventListener("click", () => {
    const value = Number(input.value) || null;
    chrome.runtime.sendMessage({ type: "setPort", port: value }, (r) => {
      void chrome.runtime.lastError;
      result.textContent = r?.ok ? "Saved. xrnrule is answering." : "Saved, but xrnrule isn't answering on that port.";
    });
  });
})();
