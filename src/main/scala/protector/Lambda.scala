package protector

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent

object Lambda {

  def main(args: Array[String]) = {
    ESNodeProtectorService.executeAndWait()
  }

  /*
   * Lambda's entry point
   */
  def handler(input: ScheduledEvent, context: Context): Unit = {
    ESNodeProtectorService.executeAndWait()
  }

}

