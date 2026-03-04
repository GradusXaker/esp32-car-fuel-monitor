/*
 * Car Fuel Monitor - ESP32
 * Fuel monitoring with OLED display and Bluetooth
 * Author: GradusXaker
 * Version: 1.0.3
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BluetoothSerial.h>
#include <EEPROM.h>

// Настройки пинов
#define FUEL_SENSOR_PIN 34  // ADC1 GPIO34
#define BUTTON_PIN 0        // GPIO0 с подтяжкой
#define LED_PIN 2           // Встроенный LED

// OLED настройки
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1
#define SCREEN_ADDRESS 0x3C

// Bluetooth
#define BT_DEVICE_NAME "CarFuelMonitor"

// EEPROM
#define EEPROM_SIZE 512
#define EEPROM_CALIB_ADDR 0

// Интервалы
#define UPDATE_INTERVAL 1000
#define AVG_SAMPLES 10

// Глобальные объекты
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);
BluetoothSerial SerialBT;

// Переменные
float fuelLevel = 0;
float fuelLiters = 0;
float fuelConsumption = 0;
float distanceTraveled = 0;
float totalFuelUsed = 0;
int calibFull = 4095;
int calibEmpty = 0;
float tankCapacity = 60;
bool isCalibrating = false;
unsigned long calibrationStart = 0;
unsigned long lastUpdate = 0;
bool bluetoothConnected = false;
unsigned long simDistanceCounter = 0;
bool displayFound = false;

// Чтение калибровки из EEPROM
void loadCalibration() {
  calibFull = EEPROM.readInt(EEPROM_CALIB_ADDR);
  calibEmpty = EEPROM.readInt(EEPROM_CALIB_ADDR + 2);
  tankCapacity = EEPROM.readFloat(EEPROM_CALIB_ADDR + 4);
  distanceTraveled = EEPROM.readFloat(EEPROM_CALIB_ADDR + 8);
  totalFuelUsed = EEPROM.readFloat(EEPROM_CALIB_ADDR + 12);
  
  // Проверка на невалидные значения
  if (calibFull < 1000 || calibFull > 4095) calibFull = 4095;
  if (calibEmpty < 0 || calibEmpty > 1000) calibEmpty = 0;
  if (tankCapacity < 10 || tankCapacity > 200) tankCapacity = 60;
  
  Serial.println("Calibration loaded: Full=" + String(calibFull) + " Empty=" + String(calibEmpty));
}

// Сохранение калибровки в EEPROM
void saveCalibration() {
  EEPROM.writeInt(EEPROM_CALIB_ADDR, calibFull);
  EEPROM.writeInt(EEPROM_CALIB_ADDR + 2, calibEmpty);
  EEPROM.writeFloat(EEPROM_CALIB_ADDR + 4, tankCapacity);
  EEPROM.writeFloat(EEPROM_CALIB_ADDR + 8, distanceTraveled);
  EEPROM.writeFloat(EEPROM_CALIB_ADDR + 12, totalFuelUsed);
  EEPROM.commit();
  Serial.println("Calibration saved");
}

// Чтение датчика топлива (усреднение)
int readFuelSensor() {
  int sum = 0;
  for (int i = 0; i < AVG_SAMPLES; i++) {
    sum += analogRead(FUEL_SENSOR_PIN);
    delay(5);
  }
  return sum / AVG_SAMPLES;
}

// Расчет уровня топлива в процентах
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

// Обновление данных о топливе
void updateFuelData() {
  int adcValue = readFuelSensor();
  float lastFuelLevel = fuelLevel;
  fuelLevel = calculateFuelLevel(adcValue);
  fuelLiters = (fuelLevel / 100.0) * tankCapacity;
  
  // Расчет израсходованного топлива
  if (lastFuelLevel > fuelLevel) {
    totalFuelUsed += ((lastFuelLevel - fuelLevel) / 100.0) * tankCapacity;
  }
  
  Serial.printf("ADC: %d | Level: %.1f%% | Liters: %.1f\n", adcValue, fuelLevel, fuelLiters);
}

// Расчет расхода топлива
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

// Отрисовка дисплея
void drawDisplay() {
  if (!displayFound) return;
  
  if (isCalibrating) {
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
    return;
  }
  
  display.clearDisplay();
  
  // Статус Bluetooth
  if (bluetoothConnected) {
    display.setCursor(0, 0);
    display.print("BT:ON");
  }
  display.setCursor(60, 0);
  display.print(millis() / 60000);
  display.print("m");
  
  // Рисунок бака
  display.drawRect(0, 15, 100, 35, SSD1306_WHITE);
  int fillWidth = (int)((98.0 * fuelLevel) / 100.0);
  display.fillRect(1, 16, fillWidth, 33, SSD1306_WHITE);
  
  // Текст
  display.setCursor(105, 15);
  display.print((int)fuelLevel);
  display.print("%");
  
  display.setCursor(105, 27);
  display.print(fuelLiters, 0);
  display.print("L");
  
  display.setCursor(105, 39);
  display.print(tankCapacity, 0);
  display.print("L");
  
  display.setCursor(0, 52);
  display.print("Cons: ");
  display.print(fuelConsumption, 1);
  display.print(" L/100km");
  
  display.display();
}

// Отправка данных по Bluetooth
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

// Обработка Bluetooth команд
void processBluetoothCommand(String cmd) {
  Serial.println("BT: " + cmd);
  
  if (cmd == "GET_STATUS") {
    sendBluetoothData();
  }
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

// Проверка кнопки
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

void setup() {
  // Инициализация последовательного порта
  Serial.begin(115200);
  Serial.println("\n\nCar Fuel Monitor v1.0.3");
  Serial.println("========================");
  
  // Настройка пинов
  pinMode(FUEL_SENSOR_PIN, INPUT);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  
  // Инициализация EEPROM
  if (!EEPROM.begin(EEPROM_SIZE)) {
    Serial.println("EEPROM init failed!");
  }
  loadCalibration();
  
  // Инициализация I2C для OLED
  Wire.begin(21, 22);  // SDA=GPIO21, SCL=GPIO22
  
  // Попытка инициализации OLED дисплея
  if (display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
    displayFound = true;
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.clearDisplay();
    Serial.println("OLED display found");
  } else {
    displayFound = false;
    Serial.println("OLED display NOT found - continuing without display");
  }
  
  // Инициализация Bluetooth
  SerialBT.begin(BT_DEVICE_NAME);
  Serial.println("Bluetooth ready: " + String(BT_DEVICE_NAME));
  
  // Приветствие на дисплее
  if (displayFound) {
    display.println("Car Fuel Monitor");
    display.println("Version 1.0.3");
    display.println("BT: " + String(BT_DEVICE_NAME));
    if (!displayFound) display.println("No OLED!");
    display.println("Loading...");
    display.display();
    delay(2000);
  }
  
  lastUpdate = millis();
  Serial.println("Setup complete");
  Serial.println("========================\n");
}

void loop() {
  // Проверка кнопки калибровки
  checkButton();
  
  // Обновление данных каждые UPDATE_INTERVAL мс
  if (millis() - lastUpdate >= UPDATE_INTERVAL) {
    updateFuelData();
    calculateConsumption();
    drawDisplay();
    sendBluetoothData();
    lastUpdate = millis();
  }
  
  // Обработка Bluetooth команд
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() > 0) {
      processBluetoothCommand(cmd);
    }
  }
  
  // Отслеживание подключения Bluetooth
  bool btState = SerialBT.hasConnected();
  if (btState != bluetoothConnected) {
    bluetoothConnected = btState;
    if (btState) {
      Serial.println("BT Connected");
    } else {
      Serial.println("BT Disconnected");
    }
  }
  
  delay(10);
}
