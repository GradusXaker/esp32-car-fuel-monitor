/*
 * Car Fuel Monitor - ESP32
 * Fuel monitoring with OLED display and Bluetooth
 * Author: GradusXaker
 * Version: 1.0.0
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BluetoothSerial.h>
#include <EEPROM.h>

#define FUEL_SENSOR_PIN 34
#define BUTTON_PIN 0
#define LED_PIN 2

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1
#define SCREEN_ADDRESS 0x3C

#define BT_DEVICE_NAME "CarFuelMonitor"

#define EEPROM_SIZE 512
#define EEPROM_CALIB_ADDR 0

#define UPDATE_INTERVAL 1000
#define AVG_SAMPLES 10

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);
BluetoothSerial SerialBT;

float fuelLevel = 0;
float fuelLiters = 0;
float fuelConsumption = 0;
float distanceTraveled = 0;
float lastFuelLevel = 0;
float totalFuelUsed = 0;

int calibFull = 4095;
int calibEmpty = 0;
float tankCapacity = 60;

bool isCalibrating = false;
unsigned long calibrationStart = 0;
unsigned long lastUpdate = 0;
bool bluetoothConnected = false;
unsigned long simDistanceCounter = 0;

void setup() {
  Serial.begin(115200);
  pinMode(FUEL_SENSOR_PIN, INPUT);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  
  EEPROM.begin(EEPROM_SIZE);
  loadCalibration();
  
  if (!display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
    Serial.println("SSD1306 allocation failed");
    for (;;);
  }
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.clearDisplay();
  
  SerialBT.begin(BT_DEVICE_NAME);
  Serial.println("Bluetooth ready: " + String(BT_DEVICE_NAME));
  
  display.println("Car Fuel Monitor");
  display.println("Version 1.0.0");
  display.println("Loading...");
  display.display();
  delay(2000);
  lastUpdate = millis();
}

void loop() {
  checkButton();
  
  if (millis() - lastUpdate >= UPDATE_INTERVAL) {
    updateFuelData();
    calculateConsumption();
    drawDisplay();
    sendBluetoothData();
    lastUpdate = millis();
  }
  
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    processBluetoothCommand(cmd);
  }
  
  bool btState = SerialBT.hasConnected();
  if (btState != bluetoothConnected) {
    bluetoothConnected = btState;
    Serial.println(btState ? "BT Connected" : "BT Disconnected");
  }
  delay(10);
}

void loadCalibration() {
  calibFull = EEPROM.readInt(EEPROM_CALIB_ADDR);
  calibEmpty = EEPROM.readInt(EEPROM_CALIB_ADDR + 2);
  tankCapacity = EEPROM.readFloat(EEPROM_CALIB_ADDR + 4);
  distanceTraveled = EEPROM.readFloat(EEPROM_CALIB_ADDR + 8);
  totalFuelUsed = EEPROM.readFloat(EEPROM_CALIB_ADDR + 12);
  
  if (calibFull < 1000 || calibFull > 4095) calibFull = 4095;
  if (calibEmpty < 0 || calibEmpty > 1000) calibEmpty = 0;
  if (tankCapacity < 10 || tankCapacity > 200) tankCapacity = 60;
}

void saveCalibration() {
  EEPROM.writeInt(EEPROM_CALIB_ADDR, calibFull);
  EEPROM.writeInt(EEPROM_CALIB_ADDR + 2, calibEmpty);
  EEPROM.writeFloat(EEPROM_CALIB_ADDR + 4, tankCapacity);
  EEPROM.writeFloat(EEPROM_CALIB_ADDR + 8, distanceTraveled);
  EEPROM.writeFloat(EEPROM_CALIB_ADDR + 12, totalFuelUsed);
  EEPROM.commit();
}

int readFuelSensor() {
  int sum = 0;
  for (int i = 0; i < AVG_SAMPLES; i++) {
    sum += analogRead(FUEL_SENSOR_PIN);
    delay(5);
  }
  return sum / AVG_SAMPLES;
}

float calculateFuelLevel(int adcValue) {
  if (adcValue > calibFull) adcValue = calibFull;
  if (adcValue < calibEmpty) adcValue = calibEmpty;
  float range = calibFull - calibEmpty;
  if (range <= 0) range = 1;
  float percent = ((float)(adcValue - calibEmpty) / range) * 100.0;
  if (percent < 0) percent = 0;
  if (percent > 100) percent = 100;
  return percent;
}

void updateFuelData() {
  int adcValue = readFuelSensor();
  lastFuelLevel = fuelLevel;
  fuelLevel = calculateFuelLevel(adcValue);
  fuelLiters = (fuelLevel / 100.0) * tankCapacity;
  
  if (lastFuelLevel > fuelLevel) {
    totalFuelUsed += ((lastFuelLevel - fuelLevel) / 100.0) * tankCapacity;
  }
  Serial.printf("ADC: %d | Level: %.1f%% | Liters: %.1f\n", adcValue, fuelLevel, fuelLiters);
}

void calculateConsumption() {
  simDistanceCounter++;
  if (simDistanceCounter >= 60) {
    distanceTraveled += 1.0;
    simDistanceCounter = 0;
    saveCalibration();
  }
  if (distanceTraveled > 0) {
    fuelConsumption = (totalFuelUsed / distanceTraveled) * 100.0;
  } else {
    fuelConsumption = 0;
  }
}

void drawDisplay() {
  if (isCalibrating) {
    drawCalibrationScreen();
    return;
  }
  
  display.clearDisplay();
  
  if (bluetoothConnected) {
    display.setCursor(0, 0);
    display.print("BT:ON");
  }
  display.setCursor(60, 0);
  display.print(millis() / 60000);
  display.print("m");
  
  drawFuelGauge(0, 15, fuelLevel);
  
  display.setCursor(0, 52);
  display.printf("Cons: %.1f L/100km | Dist: %.0f km", fuelConsumption, distanceTraveled);
  display.display();
}

void drawFuelGauge(int x, int y, float percent) {
  display.drawRect(x, y, 100, 35, SSD1306_WHITE);
  int fillWidth = (int)((98.0 * percent) / 100.0);
  display.fillRect(x + 1, y + 1, fillWidth, 33, SSD1306_WHITE);
  
  display.setCursor(x + 105, y);
  display.print((int)percent);
  display.print("%");
  
  display.setCursor(x + 105, y + 12);
  display.print(fuelLiters, 0);
  display.print("L");
  
  display.setCursor(x + 105, y + 24);
  display.print(tankCapacity, 0);
  display.print("L");
}

void drawCalibrationScreen() {
  display.clearDisplay();
  display.setCursor(0, 0);
  display.println("CALIBRATION");
  display.println("");
  display.print("ADC: ");
  display.println(readFuelSensor());
  display.print("Full: ");
  display.println(calibFull);
  display.print("Empty: ");
  display.println(calibEmpty);
  display.println("");
  display.println("Hold button:");
  display.println("3s = FULL");
  display.println("5s = EMPTY");
  display.println("7s = RESET");
  display.print("Time: ");
  display.print((millis() - calibrationStart) / 1000);
  display.print("s");
  display.display();
}

void sendBluetoothData() {
  if (!bluetoothConnected) return;
  
  String json = "{";
  json += "\"fuel_level\":" + String(fuelLevel, 1);
  json += ",\"fuel_liters\":" + String(fuelLiters, 1);
  json += ",\"consumption\":" + String(fuelConsumption, 1);
  json += ",\"distance\":" + String(distanceTraveled, 1);
  json += ",\"total_used\":" + String(totalFuelUsed, 1);
  json += ",\"adc\":" + String(readFuelSensor());
  json += ",\"tank\":" + String(tankCapacity);
  json += "}";
  SerialBT.println(json);
}

void processBluetoothCommand(String cmd) {
  Serial.println("BT: " + cmd);
  
  if (cmd == "GET_STATUS") sendBluetoothData();
  else if (cmd == "SET_FULL") {
    calibFull = readFuelSensor();
    saveCalibration();
    SerialBT.println("OK: Full=" + String(calibFull));
  }
  else if (cmd == "SET_EMPTY") {
    calibEmpty = readFuelSensor();
    saveCalibration();
    SerialBT.println("OK: Empty=" + String(calibEmpty));
  }
  else if (cmd == "RESET_STATS") {
    distanceTraveled = 0;
    totalFuelUsed = 0;
    saveCalibration();
    SerialBT.println("OK: Stats reset");
  }
  else if (cmd.startsWith("SET_TANK:")) {
    float cap = cmd.substring(9).toFloat();
    if (cap > 10 && cap < 200) {
      tankCapacity = cap;
      saveCalibration();
      SerialBT.println("OK: Tank=" + String(cap) + "L");
    }
  }
  else if (cmd == "HELP") {
    SerialBT.println("Commands: GET_STATUS, SET_FULL, SET_EMPTY, RESET_STATS, SET_TANK:XX, HELP");
  }
}

void checkButton() {
  if (digitalRead(BUTTON_PIN) == LOW) {
    if (!isCalibrating) {
      isCalibrating = true;
      calibrationStart = millis();
    }
    
    unsigned long holdTime = millis() - calibrationStart;
    
    if (holdTime >= 3000 && holdTime < 5000) {
      digitalWrite(LED_PIN, HIGH);
      if (holdTime < 3100) {
        calibFull = readFuelSensor();
        saveCalibration();
        Serial.println("Calibrated FULL: " + String(calibFull));
      }
    }
    else if (holdTime >= 5000 && holdTime < 7000) {
      if (holdTime < 5100) {
        calibEmpty = readFuelSensor();
        saveCalibration();
        Serial.println("Calibrated EMPTY: " + String(calibEmpty));
      }
    }
    else if (holdTime >= 7000) {
      if (holdTime < 7100) {
        calibFull = 4095;
        calibEmpty = 0;
        distanceTraveled = 0;
        totalFuelUsed = 0;
        saveCalibration();
        Serial.println("Reset ALL");
      }
    }
  } else {
    if (isCalibrating) {
      isCalibrating = false;
      digitalWrite(LED_PIN, LOW);
    }
  }
}
