import { readFile, writeFile, copyFile } from 'node:fs/promises';
import './generate-icon.mjs';
const root = new URL('../', import.meta.url);
const file = p => new URL(p, root);
const appPath = file('ios/App/App/AppDelegate.swift');
let app = await readFile(appPath, 'utf8');
const marker = '// TERRA NATIVE TRACKER';
app = app.split(marker)[0].trimEnd();
await writeFile(appPath, `${app}\n\n${marker}\n${await readFile(file('native/ios/TerraTracker.swift'), 'utf8')}\n`);
const storyboardPath = file('ios/App/App/Base.lproj/Main.storyboard');
let storyboard = await readFile(storyboardPath, 'utf8');
storyboard = storyboard.replace(/customClass="CAPBridgeViewController" customModule="Capacitor"/g, 'customClass="TerraViewController" customModule="App"');
if (!storyboard.includes('customClass="TerraViewController"')) throw new Error('Unable to register TerraViewController: storyboard template changed');
await writeFile(storyboardPath, storyboard);
const plistPath = file('ios/App/App/Info.plist');
let plist = await readFile(plistPath, 'utf8');
const entries = {
  NSLocationWhenInUseUsageDescription: '<string>Terra записывает выбранную тобой прогулку и открывает пройденные места на карте, в том числе при заблокированном экране.</string>',
  NSLocationAlwaysAndWhenInUseUsageDescription: '<string>Геопозиция нужна для записи начатого маршрута при выключенном экране. Запись завершается кнопкой в приложении.</string>',
  UIBackgroundModes: '<array><string>location</string></array>',
  UIFileSharingEnabled: '<true/>',
};
for (const [key, value] of Object.entries(entries)) {
  if (!plist.includes(`<key>${key}</key>`)) plist = plist.replace(/<\/dict>\s*<\/plist>/, `\t<key>${key}</key>\n\t${value}\n</dict>\n</plist>`);
}
await writeFile(plistPath, plist);
await copyFile(file('public/app-icon.png'), file('ios/App/App/Assets.xcassets/AppIcon.appiconset/AppIcon-512@2x.png'));
console.log('Terra iOS tracker, permissions and background mode configured.');
