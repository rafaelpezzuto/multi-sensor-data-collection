package org.rjpd.msdc

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class InstructionsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_instructions)

        val instructionWebView: WebView = findViewById(R.id.instructions_webview)

        val instructions = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <style>
                    body {
                      font-family: Arial, sans-serif;
                      max-width: 800px;
                      margin: 20px auto;
                      padding: 0 20px;
                      line-height: 1.4;
                      color: #333;
                    }
                
                    h2, h3 {
                      color: #4285f4;
                    }
                
                    p {
                      margin-bottom: 20px;
                    }
                
                    ol, ul {
                      margin-bottom: 20px;
                    }
                
                    ol li, ul li {
                      margin-bottom: 8px;
                    }
                
                    table {
                      border-collapse: collapse;
                      width: 100%;
                      margin-top: 20px;
                    }
                
                    th, td {
                      border: 1px solid #ddd;
                      padding: 12px;
                      text-align: left;
                    }
                
                    th {
                      background-color: #f2f2f2;
                    }
                
                    td:first-child {
                      font-weight: bold;
                    }

                    .button-is-red {
                      display: inline-block;
                      padding: 10px 20px;
                      font-size: 16px;
                      font-weight: bold;
                      text-align: center;
                      text-decoration: none;
                      background-color: #F44336;
                      color: white;
                      border: 1px solid #F44336;
                      border-radius: 4px;
                      cursor: pointer;
                      transition: background-color 0.3s;
                    }
                    
                    .button-is-purple {
                      display: inline-block;
                      padding: 10px 20px;
                      font-size: 16px;
                      font-weight: bold;
                      text-align: center;
                      text-decoration: none;
                      background-color: #BB86FC;
                      color: white;
                      border: 1px solid #BB86FC;
                      border-radius: 4px;
                      cursor: pointer;
                      transition: background-color 0.3s;
                    }
                  </style>
                </head>
                <body>
                    <h2>Welcome to the MultiSensor Data Collection App!</h2>
                    <p>This is an instructional tutorial on how to use the app for collecting data. To view the captured data, follow the instructions in the Jupyter Notebook available at <a href="https://github.com/rafaelpezzuto/multi-sensor-data-collection/blob/main/analysis/msdc_dataviz.ipynb">https://github.com/rafaelpezzuto/multi-sensor-data-collection/blob/main/analysis/msdc_dataviz.ipynb</a>.</p>
                    
                    <h3>A) Preparation</h3>
                    <ol>
                        <li>Install the mobile phone on the chest strap holder so that the display is facing the person wearing the strap;
                        <li>Wear the chest strap;</li>
                        <li>Adjust the phone holder so that the phone is in landscape mode;</li>
                        <li>Enable the auto-rotation of the mobile phone screen;</li>
                        <li>Activate the mobile phone's geolocation (GPS) service;</li>
                        <li>Open the MultiSensor Data Collection application;</li>
                        <li>Set the phone holder to an angle of <strong>G=-70</strong> degrees relative to the ground;</li>
                        <li>Keep the default installed settings.</li>
                    </ol>
                    
                    <h3>B) Data collection</h3>
                    <ol>
                        <li>Select the category;</li>
                        <li>Add the tags;</li>
                        <li>Click the <button class='button-is-red'>START</button> button;
                        <li>Stand still for <strong>T=2</strong> seconds (without moving the body);</li>
                        <li>Walk until completing <strong>P=40</strong> steps, going;</li>
                        <li>Stand still for <strong>T=2</strong> seconds;</li>
                        <li>Walk until completing </strong>P=40</strong> steps, returning;</li>
                        <li>Repeat steps 4-7 once;</li>
                        <li>Stand still for <strong>T=2</strong> seconds and click the <button class='button-is-purple'>STOP</button> button; and</li>
                        <li>Wait for the <button class='button-is-purple'>START</button> button to become active again (colored in red, like <button class='button-is-red'>START</button>) to start a new collection.</li>
                    </ol>
                    
                    <h3>Notes</h3>
                    <ul>
                        <li>The accelerometer data will be characterized by 40 peaks in one direction</li>
                        <li>The recording duration should be around 100 seconds, corresponding to 10 seconds standing still plus the time to walk 160 steps</li>
                        <li>The path must be completed twice, going and returning</li>
                        <li>The walking speed should correspond to the usual pace of the recording person</li>
                        <li>The person recording can wear any footwear or be barefoot</li>
                    </ul>

                    <p>Thank you for using our app!</p>
                </body>
            </html>
        """
        instructionWebView.loadDataWithBaseURL(null, instructions, "text/html", "UTF-8", null)
    }
}
