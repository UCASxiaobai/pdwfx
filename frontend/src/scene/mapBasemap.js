import TileLayer from "ol/layer/Tile";
import XYZ from "ol/source/XYZ";
import TileState from "ol/TileState";

const TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

function loadTileImage(tile, src) {
  const img = tile.getImage();
  let retries = 0;

  const attempt = () => {
    img.onload = () => {
      tile.setState(TileState.LOADED);
    };
    img.onerror = () => {
      if (retries < 2) {
        retries += 1;
        window.setTimeout(attempt, 400 * retries);
        return;
      }
      tile.setState(TileState.ERROR);
    };
    img.src = src;
  };

  attempt();
}

/** OpenStreetMap 底图；带简单重试，减轻网络抖动导致的白块。 */
export function createBasemapLayer() {
  return new TileLayer({
    source: new XYZ({
      url: TILE_URL,
      crossOrigin: "anonymous",
      attributions: "© OpenStreetMap contributors",
      cacheSize: 512,
      transition: 0,
      tileLoadFunction: loadTileImage
    })
  });
}
