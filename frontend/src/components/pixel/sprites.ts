/* 코드로 그리는 픽셀 스프라이트.
   각 소품은 로컬 좌표 0~1 공간의 사각형 목록으로 정의합니다.
   (배치된 크기에 맞춰 스케일되므로 해상도에 독립적입니다.)
   assetKey는 기존 OfficeAssets 카탈로그와 동일해서 저장된 방이 그대로 렌더됩니다. */

export type Rect = [x: number, y: number, w: number, h: number, color: string];

const C = {
  wood: "#a9784f", woodDark: "#7d5636", woodLight: "#c99a69",
  desk: "#d9b98a", deskDark: "#b08d5f",
  metal: "#8d97a3", metalDark: "#5d6773", metalLight: "#c3cad3",
  screen: "#3d5a80", screenLit: "#7fb4d8",
  black: "#2b2b30", white: "#f4f4f2", grey: "#b8bcc2",
  green: "#4f9a5e", greenDark: "#357245", greenLight: "#79c184",
  terracotta: "#c06e4b", terracottaDark: "#945235",
  red: "#c8503f", blue: "#4f7cac", yellow: "#e3b23c", teal: "#3f9a92",
  fabric: "#5d7f78", fabricDark: "#42605a", sofa: "#d99a5f", sofaDark: "#b3743f",
  paper: "#f6f1e4", water: "#8fd0e8",
};

export const spriteRects: Record<string, Rect[]> = {
  desk: [
    [0, .42, 1, .22, C.desk], [0, .60, 1, .10, C.deskDark],
    [.06, .70, .12, .28, C.woodDark], [.82, .70, .12, .28, C.woodDark],
    [.32, .06, .36, .28, C.black], [.36, .10, .28, .20, C.screen], [.38, .12, .10, .06, C.screenLit],
    [.46, .34, .08, .08, C.black], [.38, .42, .24, .05, C.black],
    [.70, .46, .14, .10, C.white], [.14, .46, .16, .08, C.paper],
  ],
  chair: [
    [.22, .10, .56, .40, C.fabric], [.26, .16, .48, .28, C.fabricDark],
    [.18, .48, .64, .18, C.fabric], [.44, .66, .12, .18, C.metalDark],
    [.24, .84, .52, .08, C.metalDark],
  ],
  sofa: [
    [.04, .26, .92, .40, C.sofa], [.10, .16, .34, .24, C.sofaDark], [.56, .16, .34, .24, C.sofaDark],
    [.04, .62, .92, .16, C.sofaDark], [.12, .78, .10, .14, C.woodDark], [.78, .78, .10, .14, C.woodDark],
  ],
  "meeting-table": [
    [.08, .34, .84, .34, C.desk], [.08, .64, .84, .10, C.deskDark],
    [.44, .74, .12, .18, C.woodDark], [.30, .90, .40, .08, C.woodDark],
    [.34, .40, .32, .16, C.paper], [.18, .42, .12, .10, C.white], [.70, .42, .12, .10, C.white],
  ],
  bookshelf: [
    [.06, .04, .88, .92, C.woodDark], [.12, .10, .76, .34, C.wood], [.12, .50, .76, .34, C.wood],
    [.16, .12, .10, .30, C.red], [.28, .16, .10, .26, C.blue], [.40, .12, .10, .30, C.yellow], [.52, .18, .10, .24, C.green],
    [.16, .52, .10, .30, C.teal], [.28, .56, .10, .26, C.red], [.40, .52, .10, .30, C.blue],
  ],
  whiteboard: [
    [.06, .06, .88, .58, C.white], [.06, .06, .88, .05, C.metalDark], [.06, .59, .88, .05, C.metalDark],
    [.16, .18, .44, .05, C.blue], [.16, .30, .30, .05, C.blue], [.16, .42, .52, .05, C.red],
    [.20, .64, .06, .32, C.metalDark], [.74, .64, .06, .32, C.metalDark],
  ],
  "coffee-machine": [
    [.18, .06, .64, .78, C.black], [.26, .14, .48, .22, C.metalDark], [.30, .18, .16, .10, C.screenLit],
    [.32, .44, .36, .24, C.metalDark], [.42, .46, .16, .14, C.white],
    [.14, .84, .72, .12, C.black],
  ],
  "plant-tall": [
    [.30, .60, .40, .36, C.terracotta], [.30, .60, .40, .08, C.terracottaDark],
    [.44, .18, .12, .44, C.greenDark],
    [.20, .22, .24, .12, C.green], [.56, .30, .24, .12, C.green],
    [.28, .06, .20, .14, C.greenLight], [.52, .10, .20, .14, C.greenLight],
  ],
  "plant-small": [
    [.30, .62, .40, .34, C.terracotta], [.30, .62, .40, .08, C.terracottaDark],
    [.24, .30, .24, .22, C.green], [.50, .24, .24, .24, C.greenLight], [.36, .12, .26, .22, C.greenDark],
  ],
  cactus: [
    [.30, .64, .40, .32, C.terracotta], [.30, .64, .40, .08, C.terracottaDark],
    [.40, .16, .20, .48, C.green], [.18, .34, .18, .10, C.green], [.18, .34, .10, .22, C.green],
    [.64, .42, .18, .10, C.green], [.72, .42, .10, .20, C.green],
    [.44, .10, .12, .08, C.greenLight],
  ],
  rug: [
    [.04, .22, .92, .56, C.red], [.12, .32, .76, .36, C.paper], [.26, .40, .48, .20, C.red],
  ],
  clock: [
    [.14, .10, .72, .72, C.white], [.20, .16, .60, .60, C.paper],
    [.46, .26, .06, .24, C.black], [.50, .46, .18, .06, C.black],
  ],
  picture: [
    [.06, .10, .88, .74, C.woodDark], [.14, .18, .72, .58, C.water],
    [.14, .56, .72, .20, C.green], [.24, .38, .18, .18, C.greenDark], [.62, .26, .14, .14, C.yellow],
  ],
  trophy: [
    [.30, .12, .40, .34, C.yellow], [.18, .18, .12, .16, C.yellow], [.70, .18, .12, .16, C.yellow],
    [.44, .46, .12, .22, C.deskDark], [.28, .68, .44, .16, C.woodDark],
  ],
  aquarium: [
    [.08, .20, .84, .58, C.metalDark], [.12, .24, .76, .50, C.water],
    [.12, .58, .76, .16, C.desk], [.34, .38, .16, .10, C.red], [.30, .40, .06, .06, C.red],
    [.60, .30, .10, .16, C.greenDark], [.10, .78, .80, .12, C.black],
  ],
  lamp: [
    [.26, .08, .48, .26, C.yellow], [.22, .30, .56, .06, C.yellow],
    [.46, .36, .08, .46, C.metalDark], [.30, .82, .40, .10, C.black],
  ],
  "water-cooler": [
    [.32, .04, .36, .26, C.water], [.26, .30, .48, .56, C.white], [.26, .30, .48, .06, C.grey],
    [.42, .46, .16, .10, C.blue], [.22, .86, .56, .10, C.grey],
  ],
  banner: [
    [.24, .06, .52, .70, C.black], [.24, .76, .26, .14, C.black], [.50, .76, .26, .14, C.black],
    [.34, .20, .32, .06, C.white], [.34, .32, .22, .05, C.grey], [.34, .42, .28, .05, C.grey],
  ],
  dog: [
    [.22, .52, .56, .30, C.desk], [.10, .30, .34, .32, C.deskDark],
    [.04, .26, .12, .20, C.woodDark], [.16, .38, .05, .05, C.black], [.30, .38, .05, .05, C.black],
    [.20, .48, .10, .06, C.black], [.76, .34, .10, .22, C.desk],
    [.22, .82, .10, .12, C.deskDark], [.60, .82, .10, .12, C.deskDark],
  ],
  cat: [
    [.24, .54, .54, .28, C.grey], [.12, .32, .32, .30, C.metalLight],
    [.10, .20, .10, .14, C.metalLight], [.36, .20, .10, .14, C.metalLight],
    [.18, .40, .05, .05, C.black], [.32, .40, .05, .05, C.black],
    [.76, .30, .08, .26, C.grey], [.26, .82, .10, .12, C.grey], [.62, .82, .10, .12, C.grey],
  ],
};

export const PALETTE = C;
