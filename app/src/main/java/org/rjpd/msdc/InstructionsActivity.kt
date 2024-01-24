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
                        <li>Set the phone holder to an angle of <strong>G</strong> degrees relative to the ground;</li>
                        <li>Keep the default installed settings.</li>
                    </ol>
                    
                    <h3>B) Data collection</h3>
                    <ol>
                        <li>Select the category;</li>
                        <li>Add the tags;</li>
                        <li>Click the <button class='button-is-red'>START</button> button and stand still for <strong>T</strong> seconds (without moving the body);</li>
                        <li>Perform the activity (walk until completing <strong>P</strong> steps), and upon finishing, stand still for <strong>T</strong> seconds (without moving the body);</li>
                        <li>Click the <button class='button-is-purple'>STOP</button> button;</li>
                        <li>Wait for the <button class='button-is-purple'>START</button> button to become active again (colored in red, like <button class='button-is-red'>START</button>) to start a new collection.</li>
                    </ol>
                    
                    <h3>Recommended parameters</h3>
                    <ul>
                        <li>T = 2 seconds</li>
                        <li>P = 40 steps</li>
                        <li>G = 70 degrees</li>
                    </ul>
                    
                    <h3>Notes</h3>
                    <ul>
                        <li>The accelerometer data will be characterized by 40 peaks;</li>
                        <li>The recording duration should be around 30 to 35 seconds, corresponding to four seconds standing still plus the time to walk 40 steps;</li>
                        <li>The path should be one-way only;</li>
                        <li>The walking speed should correspond to the usual pace of the recording person;</li>
                        <li>The person recording can wear any footwear or be barefoot.</li>
                    </ul>
                    
                    <h3>Here are the suggested categories and tags to be considered in the data collection</h3>
                    <table border="1" cellpadding="2">
                      <tr>
                        <th>Category</th>
                        <th>Tag</th>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Aerial vegetation</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Bench</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Bike rack</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Black ice</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Car barrier</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Construction material</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Dirt</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Fence</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Fire hydrant</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Garage entrance</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Ground vegetation</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Manhole cover</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Parked vehicle</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Person</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Pole</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Potted plant</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Puddle</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Rock</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Sewer cover</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Transit sign</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Trash can</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Tree leaves</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Trunck</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Water channel</td>
                      </tr>
                      <tr>
                        <td>Obstacles</td>
                        <td>Water fountain</td>
                      </tr>
                      <tr>
                        <td>Pavement condition</td>
                        <td>Broken</td>
                      </tr>
                      <tr>
                        <td>Pavement condition</td>
                        <td>Corrugation</td>
                      </tr>
                      <tr>
                        <td>Pavement condition</td>
                        <td>Cracked</td>
                      </tr>
                      <tr>
                        <td>Pavement condition</td>
                        <td>Detached</td>
                      </tr>
                      <tr>
                        <td>Pavement condition</td>
                        <td>Patching</td>
                      </tr>
                      <tr>
                        <td>Pavement condition</td>
                        <td>Pothole</td>
                      </tr>
                      <tr>
                        <td>Sidewalk geometry</td>
                        <td>Height difference</td>
                      </tr>
                      <tr>
                        <td>Sidewalk geometry</td>
                        <td>Narrow</td>
                      </tr>
                      <tr>
                        <td>Sidewalk geometry</td>
                        <td>Steep</td>
                      </tr>
                      <tr>
                        <td>Sidewalk structure</td>
                        <td>Bioswale</td>
                      </tr>
                      <tr>
                        <td>Sidewalk structure</td>
                        <td>Curb cut</td>
                      </tr>
                      <tr>
                        <td>Sidewalk structure</td>
                        <td>Curb ramp</td>
                      </tr>
                      <tr>
                        <td>Sidewalk structure</td>
                        <td>Footbridge</td>
                      </tr>
                      <tr>
                        <td>Sidewalk structure</td>
                        <td>Friction strip</td>
                      </tr>
                      <tr>
                        <td>Sidewalk structure</td>
                        <td>Ramp</td>
                      </tr>
                      <tr>
                        <td>Sidewalk structure</td>
                        <td>Stairs</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Asphalt</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Bluestone</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Brick</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Coating</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Cobblestone</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Concrete</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Concrete with aggregates</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Grass</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Gravel</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Large pavers</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Red brick</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Slab</td>
                      </tr>
                      <tr>
                        <td>Surface type</td>
                        <td>Tactile paving</td>
                      </tr>
                    </table>

                    <p>Thank you for using our app!</p>
                </body>
            </html>
        """
        instructionWebView.loadDataWithBaseURL(null, instructions, "text/html", "UTF-8", null)
    }
}
