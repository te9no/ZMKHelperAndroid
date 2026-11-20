interface UsbAlternate {
  alternateSetting: number;
  interfaceClass: number;
  endpoints: Array<{ endpointNumber: number; direction: "in" | "out"; type: string }>;
}

interface UsbInterface {
  interfaceNumber: number;
  alternate: UsbAlternate;
  alternates: UsbAlternate[];
}

interface UsbDeviceLike {
  productName?: string;
  vendorId: number;
  productId: number;
  opened: boolean;
  configuration?: { configurationValue: number; interfaces: UsbInterface[] };
  open(): Promise<void>;
  close(): Promise<void>;
  selectConfiguration(value: number): Promise<void>;
  claimInterface(interfaceNumber: number): Promise<void>;
  selectAlternateInterface(interfaceNumber: number, alternateSetting: number): Promise<void>;
  controlTransferOut(
    setup: { requestType: "class"; recipient: "interface"; request: number; value: number; index: number },
    data?: BufferSource,
  ): Promise<unknown>;
  transferIn(endpointNumber: number, length: number): Promise<{ data?: DataView }>;
}

interface UsbApi {
  requestDevice(options: { filters: Array<{ vendorId?: number; productId?: number }> }): Promise<UsbDeviceLike>;
}

function usbApi(): UsbApi | undefined {
  return (navigator as Navigator & { usb?: UsbApi }).usb;
}

export function webUsbAvailable(): boolean {
  return Boolean(usbApi());
}

export class CdcConsole {
  private device?: UsbDeviceLike;
  private communicationInterface = -1;
  private inputEndpoint = -1;
  private reading = false;

  async connect(baudRate: number, onData: (text: string) => void): Promise<string> {
    const api = usbApi();
    if (!api) throw new Error("このブラウザはWebUSBに対応していません。Android Chromeを使用してください");
    const device = await api.requestDevice({ filters: [] });
    await this.openCdc(device);
    await this.setLineCoding(baudRate);
    await this.setControlLineState(true, true);
    this.device = device;
    this.reading = true;
    void this.readLoop(onData);
    return device.productName || `${hex(device.vendorId)}:${hex(device.productId)}`;
  }

  async triggerBootloader(): Promise<string> {
    const api = usbApi();
    if (!api) throw new Error("このブラウザはWebUSBに対応していません。Android Chromeを使用してください");
    const device = await api.requestDevice({ filters: [] });
    await this.openCdc(device);
    this.device = device;
    try {
      await this.setLineCoding(1200);
      await this.setControlLineState(true, false);
      await delay(250);
      await this.setControlLineState(false, false);
      await delay(250);
      return device.productName || `${hex(device.vendorId)}:${hex(device.productId)}`;
    } finally {
      await this.disconnect();
    }
  }

  async disconnect(): Promise<void> {
    this.reading = false;
    const device = this.device;
    this.device = undefined;
    if (device?.opened) await device.close().catch(() => undefined);
  }

  private async openCdc(device: UsbDeviceLike): Promise<void> {
    await device.open();
    if (!device.configuration) await device.selectConfiguration(1);
    const interfaces = device.configuration?.interfaces || [];
    const communication = interfaces.find((entry) => entry.alternates.some((alternate) => alternate.interfaceClass === 0x02));
    const data = interfaces.find((entry) => entry.alternates.some((alternate) => alternate.interfaceClass === 0x0a));
    if (!communication || !data) {
      await device.close();
      throw new Error("選択したUSB deviceにCDC ACM interfaceがありません");
    }
    const communicationAlternate = communication.alternates.find((alternate) => alternate.interfaceClass === 0x02)!;
    const dataAlternate = data.alternates.find((alternate) => alternate.interfaceClass === 0x0a)!;
    await device.claimInterface(communication.interfaceNumber);
    if (communication.alternate.alternateSetting !== communicationAlternate.alternateSetting) {
      await device.selectAlternateInterface(communication.interfaceNumber, communicationAlternate.alternateSetting);
    }
    await device.claimInterface(data.interfaceNumber);
    if (data.alternate.alternateSetting !== dataAlternate.alternateSetting) {
      await device.selectAlternateInterface(data.interfaceNumber, dataAlternate.alternateSetting);
    }
    const input = dataAlternate.endpoints.find((endpoint) => endpoint.direction === "in" && endpoint.type === "bulk");
    if (!input) {
      await device.close();
      throw new Error("CDC data interfaceにbulk IN endpointがありません");
    }
    this.device = device;
    this.communicationInterface = communication.interfaceNumber;
    this.inputEndpoint = input.endpointNumber;
  }

  private async setLineCoding(baudRate: number): Promise<void> {
    if (!this.device) throw new Error("CDC deviceが接続されていません");
    const data = new Uint8Array(7);
    const view = new DataView(data.buffer);
    view.setUint32(0, baudRate, true);
    data[4] = 0;
    data[5] = 0;
    data[6] = 8;
    await this.device.controlTransferOut(
      { requestType: "class", recipient: "interface", request: 0x20, value: 0, index: this.communicationInterface },
      data,
    );
  }

  private async setControlLineState(dtr: boolean, rts: boolean): Promise<void> {
    if (!this.device) throw new Error("CDC deviceが接続されていません");
    await this.device.controlTransferOut({
      requestType: "class",
      recipient: "interface",
      request: 0x22,
      value: (dtr ? 1 : 0) | (rts ? 2 : 0),
      index: this.communicationInterface,
    });
  }

  private async readLoop(onData: (text: string) => void): Promise<void> {
    const decoder = new TextDecoder();
    while (this.reading && this.device?.opened) {
      try {
        const result = await this.device.transferIn(this.inputEndpoint, 256);
        if (result.data?.byteLength) {
          onData(decoder.decode(new Uint8Array(result.data.buffer, result.data.byteOffset, result.data.byteLength), { stream: true }));
        }
      } catch (error) {
        if (this.reading) onData(`\n[USB disconnected: ${error instanceof Error ? error.message : String(error)}]\n`);
        break;
      }
    }
    this.reading = false;
  }
}

function hex(value: number): string {
  return value.toString(16).padStart(4, "0").toUpperCase();
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
