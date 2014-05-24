from ..tracer import Tracer

__all__ = ('TRACER',)

TRACER = Tracer(predicate=Tracer.env_filter('TWITTER_COMMON_PYTHON_HTTP'),
                prefix='twitter.common.python.http: ')
