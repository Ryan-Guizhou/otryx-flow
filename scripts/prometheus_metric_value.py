"""Normalize numeric values parsed from the Prometheus text exposition format."""

from decimal import Decimal, InvalidOperation


def normalize_metric_value(value: str) -> str:
    """Return a finite metric value with integral values written without a decimal.

    Prometheus gauge exposition may encode an integer state (for example 1)
    as a floating point value (1.0). String comparisons then produce a false
    health check failure, so normalize integral values before shell checks.
    """
    try:
        number = Decimal(value.strip())
    except (InvalidOperation, AttributeError) as error:
        raise ValueError("Prometheus metric value must be numeric") from error
    if not number.is_finite():
        raise ValueError("Prometheus metric value must be finite")
    if number == number.to_integral_value():
        return str(int(number))
    return format(number.normalize(), "f")
