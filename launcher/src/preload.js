const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('hc', {
  install: () => ipcRenderer.invoke('install'),
  launch: () => ipcRenderer.invoke('launch'),
  close: () => ipcRenderer.invoke('close'),
  minimize: () => ipcRenderer.invoke('minimize'),
  openFolder: () => ipcRenderer.invoke('openFolder'),
  onProgress: (cb) => ipcRenderer.on('progress', (_e, d) => cb(d)),
});
