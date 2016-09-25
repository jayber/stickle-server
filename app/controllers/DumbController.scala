package controllers

import play.api.mvc.{Action, Controller}

class DumbController extends Controller {
  def healthCheck = Action {
    Ok("Health check")
  }
}
