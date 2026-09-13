import { deflateSync } from 'node:zlib';
import { writeFile } from 'node:fs/promises';
// Rasterize Terra's arrow mark; no external image service or asset dependency.
const size = 1024, bytes = Buffer.alloc((size * 3 + 1) * size);
const polygon = [[.22,.70],[.59,.33],[.28,.33],[.28,.23],[.77,.23],[.77,.72],[.67,.72],[.67,.41],[.30,.78]];
function inside(x,y) {
  let result = false;
  for (let i=0,j=polygon.length-1;i<polygon.length;j=i++) {
    const [xi,yi]=polygon[i],[xj,yj]=polygon[j];
    if ((yi>y)!==(yj>y) && x<(xj-xi)*(y-yi)/(yj-yi)+xi) result=!result;
  }
  return result;
}
for(let y=0;y<size;y++) for(let x=0;x<size;x++) {
  let light=0;
  for(const ox of [.25,.75]) for(const oy of [.25,.75]) {
    const px=(x+ox)/size,py=(y+oy)/size;
    if (inside(px,py)) light+=.25;
  }
  const offset=y*(size*3+1)+1+x*3;
  [32,35,40].forEach((v,c)=>bytes[offset+c]=Math.round(v*(1-light)+[255,117,64][c]*light));
}
function crc(buffer) { let c=0xffffffff; for(const b of buffer){c^=b;for(let k=0;k<8;k++)c=(c>>>1)^((c&1)?0xedb88320:0);}return(c^0xffffffff)>>>0; }
function chunk(type,data){const name=Buffer.from(type),out=Buffer.alloc(data.length+12);out.writeUInt32BE(data.length);name.copy(out,4);data.copy(out,8);out.writeUInt32BE(crc(Buffer.concat([name,data])),data.length+8);return out;}
const header=Buffer.alloc(13);header.writeUInt32BE(size);header.writeUInt32BE(size,4);header[8]=8;header[9]=2;
const png=Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(bytes)),chunk('IEND',Buffer.alloc(0))]);
await writeFile(new URL('../public/app-icon.png',import.meta.url),png);
